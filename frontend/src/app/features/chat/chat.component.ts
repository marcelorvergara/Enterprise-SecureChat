import {
  AfterViewChecked,
  Component,
  ElementRef,
  OnDestroy,
  OnInit,
  ViewChild,
  inject,
} from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { Location } from '@angular/common';
import { Subscription } from 'rxjs';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { CdkTextareaAutosize, TextFieldModule } from '@angular/cdk/text-field';
import { AuthService } from '@auth0/auth0-angular';
import { MatMenuModule } from '@angular/material/menu';
import { ChatService, Message, SourceCitation, StreamEvent } from '../../core/services/chat.service';
import { ConversationExportService } from '../../core/services/conversation-export.service';
import { SafeMarkdownPipe } from '../../shared/pipes/safe-markdown.pipe';
import { BuUploadModalComponent } from './bu-upload-modal.component';
import { SourcePreviewDialogComponent } from './source-preview-dialog.component';

const ROLES_CLAIM = 'https://enpsecurechat.com/roles';
const INGEST_ROLES = new Set(['bu-user', 'reserves-management', 'reserves-coordination']);

interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  sources: SourceCitation[];
  fgaApplied: boolean;
  dlpEntitiesRedacted: number;
}

@Component({
  selector: 'app-chat',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatIconModule,
    MatChipsModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
    MatDialogModule,
    MatSnackBarModule,
    MatMenuModule,
    TextFieldModule,
    SafeMarkdownPipe,
  ],
  templateUrl: './chat.component.html',
  styleUrls: ['./chat.component.css'],
})
export class ChatComponent implements OnInit, AfterViewChecked, OnDestroy {
  @ViewChild('messageThread') private messageThread!: ElementRef<HTMLDivElement>;
  @ViewChild(CdkTextareaAutosize) private autosize!: CdkTextareaAutosize;
  @ViewChild('fileInput') private fileInput!: ElementRef<HTMLInputElement>;

  private readonly chatService = inject(ChatService);
  private readonly exportService = inject(ConversationExportService);
  private readonly auth = inject(AuthService);
  private readonly route = inject(ActivatedRoute);
  private readonly location = inject(Location);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private routeSub?: Subscription;
  private authSub?: Subscription;

  canIngestDocuments = false;
  conversationTitle = 'Conversation';

  openBuUpload(): void {
    this.dialog.open(BuUploadModalComponent, { width: '420px' });
  }

  readonly MAX_MESSAGE_LENGTH = 4000;

  get charCount(): number {
    return this.messageControl.value?.length ?? 0;
  }

  messages: ChatMessage[] = [];
  suggestions: string[] = [];
  readonly messageControl = new FormControl('');
  loading = false;
  conversationId?: string;
  attachedFile?: File;

  private pendingScroll = false;
  private pendingRequest?: Subscription;

  ngOnInit(): void {
    this.authSub = this.auth.user$.subscribe(user => {
      const roles: string[] = user?.[ROLES_CLAIM] ?? [];
      this.canIngestDocuments = roles.some(r => INGEST_ROLES.has(r));
    });
    this.routeSub = this.route.paramMap.subscribe(params => {
      const id = params.get('id') ?? undefined;
      if (id === this.conversationId) return;
      this.reset();
      if (id) {
        this.conversationId = id;
        this.loadHistory(id);
      }
    });
  }

  ngOnDestroy(): void {
    this.routeSub?.unsubscribe();
    this.authSub?.unsubscribe();
    this.pendingRequest?.unsubscribe();
  }

  stopGenerating(): void {
    this.pendingRequest?.unsubscribe();
    this.pendingRequest = undefined;
    this.loading = false;
    this.pendingScroll = true;
  }

  private reset(): void {
    this.pendingRequest?.unsubscribe();
    this.pendingRequest = undefined;
    this.messages = [];
    this.suggestions = [];
    this.conversationId = undefined;
    this.conversationTitle = 'Conversation';
    this.loading = false;
    this.pendingScroll = false;
    this.attachedFile = undefined;
  }

  private loadHistory(conversationId: string): void {
    this.loading = true;
    this.chatService.getConversation(conversationId).subscribe({
      next: conv => { this.conversationTitle = conv.title ?? 'Conversation'; },
    });
    this.chatService.getMessages(conversationId).subscribe({
      next: (msgs: Message[]) => {
        this.messages = msgs.map(m => ({
          role: m.role,
          content: m.content,
          sources: this.deduplicateSources(m.sources ?? []),
          fgaApplied: false,
          dlpEntitiesRedacted: 0,
        }));
        this.loading = false;
        this.pendingScroll = true;
      },
      error: () => { this.loading = false; },
    });
  }

  ngAfterViewChecked(): void {
    if (this.pendingScroll) {
      this.scrollToBottom();
      this.pendingScroll = false;
    }
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (file) this.attachedFile = file;
    input.value = '';
  }

  removeFile(): void {
    this.attachedFile = undefined;
  }

  onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      this.sendMessage();
    }
  }

  submitSuggestion(text: string): void {
    this.suggestions = [];
    this.messageControl.setValue(text);
    this.sendMessage();
  }

  sendMessage(): void {
    const text = this.messageControl.value?.trim();
    if (!text || this.loading) return;

    this.suggestions = [];
    const fileToSend = this.attachedFile;
    this.attachedFile = undefined;

    const displayContent = fileToSend
      ? `${text}\n[Attached: ${fileToSend.name}]`
      : text;

    this.messages.push({
      role: 'user',
      content: displayContent,
      sources: [],
      fgaApplied: false,
      dlpEntitiesRedacted: 0,
    });
    this.messageControl.reset();
    this.loading = true;
    this.pendingScroll = true;

    const wasNew = !this.conversationId;

    if (fileToSend) {
      // Document verification stays blocking — DLP requires the full document context
      this.pendingRequest = this.chatService
        .verifyDocument(text, this.conversationId, fileToSend)
        .subscribe({
          next: response => {
            this.conversationId = response.conversationId;
            if (wasNew) {
              this.chatService.notifyConversationCreated();
              this.location.replaceState(`/c/${response.conversationId}`);
            }
            this.messages.push({
              role: 'assistant',
              content: response.answer,
              sources: this.deduplicateSources(response.sources ?? []),
              fgaApplied: response.fgaApplied,
              dlpEntitiesRedacted: response.dlpEntitiesRedacted,
            });
            this.suggestions = response.suggestions ?? [];
            this.loading = false;
            this.pendingRequest = undefined;
            this.pendingScroll = true;
          },
          error: () => this.handleStreamError(),
        });
      return;
    }

    // Regular chat — sentence-buffered SSE stream
    // Push a placeholder message immediately so the UI shows a typing indicator
    this.messages.push({
      role: 'assistant',
      content: '',
      sources: [],
      fgaApplied: false,
      dlpEntitiesRedacted: 0,
    });
    const streamingMsg = this.messages[this.messages.length - 1];

    this.pendingRequest = this.chatService
      .sendMessageStream({ message: text, conversationId: this.conversationId })
      .subscribe({
        next: (event: StreamEvent) => {
          if (event.type === 'content') {
            streamingMsg.content += (streamingMsg.content ? ' ' : '') + event.text;
            this.pendingScroll = true;
          } else if (event.type === 'metadata') {
            this.conversationId = event.conversationId;
            streamingMsg.sources = this.deduplicateSources(event.sources ?? []);
            streamingMsg.fgaApplied = event.fgaApplied;
            streamingMsg.dlpEntitiesRedacted = event.dlpEntitiesRedacted;
            this.suggestions = event.suggestions ?? [];
            if (wasNew) {
              this.chatService.notifyConversationCreated();
              this.location.replaceState(`/c/${event.conversationId}`);
            }
          }
        },
        error: () => {
          if (!streamingMsg.content) {
            streamingMsg.content = 'Something went wrong. Please try again.';
          }
          this.loading = false;
          this.pendingRequest = undefined;
          this.pendingScroll = true;
        },
        complete: () => {
          this.loading = false;
          this.pendingRequest = undefined;
          this.pendingScroll = true;
        },
      });
  }

  private handleStreamError(): void {
    this.messages.push({
      role: 'assistant',
      content: 'Something went wrong. Please try again.',
      sources: [],
      fgaApplied: false,
      dlpEntitiesRedacted: 0,
    });
    this.loading = false;
    this.pendingRequest = undefined;
    this.pendingScroll = true;
  }

  private deduplicateSources(sources: SourceCitation[]): SourceCitation[] {
    const best = new Map<string, SourceCitation>();
    for (const src of sources) {
      const existing = best.get(src.sourceFile);
      if (!existing || src.score > existing.score) {
        best.set(src.sourceFile, src);
      }
    }
    return Array.from(best.values());
  }

  openSourcePreview(source: SourceCitation): void {
    this.dialog.open(SourcePreviewDialogComponent, {
      width: '600px',
      data: { conversationId: this.conversationId, citation: source },
    });
  }

  exportMarkdown(): void {
    this.exportService.exportMarkdown(this.conversationTitle, this.messages);
  }

  exportPdf(): void {
    this.exportService.exportPdf(this.conversationTitle, this.messages);
  }

  private scrollToBottom(): void {
    try {
      const el = this.messageThread.nativeElement;
      el.scrollTop = el.scrollHeight;
    } catch {}
  }
}
