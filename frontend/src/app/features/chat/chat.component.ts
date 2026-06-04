import {
  AfterViewChecked,
  Component,
  ElementRef,
  ViewChild,
  inject,
} from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { CdkTextareaAutosize, TextFieldModule } from '@angular/cdk/text-field';
import { ChatService, SourceCitation } from '../../core/services/chat.service';
import { SafeMarkdownPipe } from '../../shared/pipes/safe-markdown.pipe';

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
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    MatChipsModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
    TextFieldModule,
    SafeMarkdownPipe,
  ],
  templateUrl: './chat.component.html',
  styleUrls: ['./chat.component.css'],
})
export class ChatComponent implements AfterViewChecked {
  @ViewChild('messageThread') private messageThread!: ElementRef<HTMLDivElement>;
  @ViewChild(CdkTextareaAutosize) private autosize!: CdkTextareaAutosize;

  private readonly chatService = inject(ChatService);

  messages: ChatMessage[] = [];
  readonly messageControl = new FormControl('');
  loading = false;
  conversationId?: string;

  private pendingScroll = false;

  ngAfterViewChecked(): void {
    if (this.pendingScroll) {
      this.scrollToBottom();
      this.pendingScroll = false;
    }
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

    this.messages.push({
      role: 'user',
      content: text,
      sources: [],
      fgaApplied: false,
      dlpEntitiesRedacted: 0,
    });
    this.messageControl.reset();
    this.loading = true;
    this.pendingScroll = true;

    this.chatService
      .sendMessage({ message: text, conversationId: this.conversationId })
      .subscribe({
        next: response => {
          this.conversationId = response.conversationId;
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
