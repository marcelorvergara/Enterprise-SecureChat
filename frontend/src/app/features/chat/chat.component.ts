import {
  AfterViewChecked,
  Component,
  ElementRef,
  OnDestroy,
  OnInit,
  ViewChild,
  inject,
} from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { CdkTextareaAutosize, TextFieldModule } from '@angular/cdk/text-field';
import { ChatService, Message, SourceCitation } from '../../core/services/chat.service';
import { SafeMarkdownPipe } from '../../shared/pipes/safe-markdown.pipe';
import { BuUploadModalComponent } from './bu-upload-modal.component';
import { keycloak } from '../../core/auth/keycloak.init';

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
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private routeSub?: Subscription;

  get canIngestDocuments(): boolean {
    const roles = keycloak.realmAccess?.roles ?? [];
    return roles.some(r => ['bu-user', 'reserves-management', 'reserves-coordination'].includes(r));
  }

  openBuUpload(): void {
    this.dialog.open(BuUploadModalComponent, { width: '420px' });
  }

  messages: ChatMessage[] = [];
  readonly messageControl = new FormControl('');
  loading = false;
  conversationId?: string;
  attachedFile?: File;

  private pendingScroll = false;

  ngOnInit(): void {
    this.routeSub = this.route.paramMap.subscribe(params => {
      const id = params.get('id') ?? undefined;
      this.reset();
      if (id) {
        this.conversationId = id;
        this.loadHistory(id);
      }
    });
  }

  ngOnDestroy(): void {
    this.routeSub?.unsubscribe();
  }

  private reset(): void {
    this.messages = [];
    this.conversationId = undefined;
    this.loading = false;
    this.pendingScroll = false;
    this.attachedFile = undefined;
  }

  private loadHistory(conversationId: string): void {
    this.loading = true;
    this.chatService.getMessages(conversationId).subscribe({
      next: (msgs: Message[]) => {
        this.messages = msgs.map(m => ({
          role: m.role,
          content: m.content,
          sources: m.sources ?? [],
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

  sendMessage(): void {
    const text = this.messageControl.value?.trim();
    if (!text || this.loading) return;

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
    const request$ = fileToSend
      ? this.chatService.verifyDocument(text, this.conversationId, fileToSend)
      : this.chatService.sendMessage({ message: text, conversationId: this.conversationId });

    request$.subscribe({
      next: response => {
        this.conversationId = response.conversationId;
        if (wasNew) {
          this.chatService.notifyConversationCreated();
          this.router.navigate(['/c', response.conversationId], { replaceUrl: true });
        }
        this.messages.push({
          role: 'assistant',
          content: response.answer,
          sources: response.sources ?? [],
          fgaApplied: response.fgaApplied,
          dlpEntitiesRedacted: response.dlpEntitiesRedacted,
        });
        this.loading = false;
        this.pendingScroll = true;
      },
      error: () => {
        this.messages.push({
          role: 'assistant',
          content: 'Something went wrong. Please try again.',
          sources: [],
          fgaApplied: false,
          dlpEntitiesRedacted: 0,
        });
        this.loading = false;
        this.pendingScroll = true;
      },
    });
  }

  private scrollToBottom(): void {
    try {
      const el = this.messageThread.nativeElement;
      el.scrollTop = el.scrollHeight;
    } catch {}
  }
}
