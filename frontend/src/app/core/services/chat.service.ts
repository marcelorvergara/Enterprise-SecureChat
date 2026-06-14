import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, Subject, firstValueFrom } from 'rxjs';
import { AuthService } from '@auth0/auth0-angular';
import { environment } from '../../../environments/environment';

export interface ChatRequest {
  message: string;
  conversationId?: string;
}

export interface SourceCitation {
  chunkId: string;
  sourceFile: string;
  subjectPath: string;
  pageNumber?: number;
  sheetName?: string;
  score: number;
}

export interface SourcePreview {
  chunkId: string;
  chunkText: string;
  sourceFile: string;
  subjectPath: string;
  pageNumber?: number;
  sheetName?: string;
}

export interface ChatResponse {
  answer: string;
  conversationId: string;
  sources: SourceCitation[];
  fgaApplied: boolean;
  dlpEntitiesRedacted: number;
  suggestions: string[];
}

export type StreamEvent =
  | { type: 'content'; text: string }
  | { type: 'metadata'; conversationId: string; sources: SourceCitation[];
      fgaApplied: boolean; dlpEntitiesRedacted: number; suggestions: string[] };

export interface Conversation {
  id: string;
  createdAt: string;
  title?: string;
}

export interface Message {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  sources: SourceCitation[] | null;
  createdAt: string;
}

@Injectable({ providedIn: 'root' })
export class ChatService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly conversationsRefresh$ = new Subject<void>();
  readonly refresh$ = this.conversationsRefresh$.asObservable();

  sendMessage(request: ChatRequest): Observable<ChatResponse> {
    return this.http.post<ChatResponse>('/api/chat', request);
  }

  /**
   * Streams the chat response sentence-by-sentence via SSE.
   * Uses fetch() + ReadableStream because EventSource does not support custom headers.
   * Emits StreamEvent items: 'content' for each DLP-cleaned sentence,
   * then one 'metadata' event at the end with sources, suggestions, and conversationId.
   * Unsubscribing aborts the fetch (connects to the stopGenerating() cancel button).
   */
  sendMessageStream(request: ChatRequest): Observable<StreamEvent> {
    return new Observable(observer => {
      const controller = new AbortController();

      (async () => {
        try {
          const token = await firstValueFrom(this.auth.getAccessTokenSilently());
          const response = await fetch(`${environment.streamApiBase}/api/chat/stream`, {
            method: 'POST',
            headers: {
              'Content-Type': 'application/json',
              'Authorization': `Bearer ${token}`,
            },
            body: JSON.stringify(request),
            signal: controller.signal,
          });

          if (!response.ok || !response.body) {
            observer.error(new Error(`HTTP ${response.status}`));
            return;
          }

          const reader = response.body.getReader();
          const decoder = new TextDecoder();
          let buffer = '';

          while (true) {
            const { value, done } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });

            // SSE blocks are separated by a blank line (\n\n)
            const blocks = buffer.split('\n\n');
            buffer = blocks.pop() ?? '';

            for (const block of blocks) {
              if (!block.trim()) continue;
              const lines = block.split('\n');
              let eventName = 'message';
              let dataLine = '';
              for (const line of lines) {
                if (line.startsWith('event:')) eventName = line.substring(6).trim();
                else if (line.startsWith('data:')) dataLine = line.substring(5).trim();
              }
              if (!dataLine) continue;

              if (eventName === 'metadata') {
                try { observer.next({ type: 'metadata', ...JSON.parse(dataLine) }); } catch {}
              } else {
                observer.next({ type: 'content', text: dataLine });
              }
            }
          }
          observer.complete();
        } catch (err: unknown) {
          if (err instanceof DOMException && err.name === 'AbortError') {
            observer.complete();
          } else {
            observer.error(err);
          }
        }
      })();

      return () => controller.abort();
    });
  }

  getConversations(): Observable<Conversation[]> {
    return this.http.get<Conversation[]>('/api/conversations');
  }

  getConversation(conversationId: string): Observable<Conversation> {
    return this.http.get<Conversation>(`/api/conversations/${conversationId}`);
  }

  getMessages(conversationId: string): Observable<Message[]> {
    return this.http.get<Message[]>(`/api/conversations/${conversationId}/messages`);
  }

  verifyDocument(message: string, conversationId: string | undefined, file: File): Observable<ChatResponse> {
    const form = new FormData();
    form.append('message', message);
    if (conversationId) form.append('conversationId', conversationId);
    form.append('file', file, file.name);
    // Do NOT set Content-Type manually — HttpClient sets it with the multipart boundary
    return this.http.post<ChatResponse>('/api/chat/verify', form);
  }

  getSourcePreview(conversationId: string, chunkId: string): Observable<SourcePreview> {
    return this.http.get<SourcePreview>(`/api/conversations/${conversationId}/sources/${chunkId}`);
  }

  deleteConversation(id: string): Observable<void> {
    return this.http.delete<void>(`/api/conversations/${id}`);
  }

  notifyConversationCreated(): void {
    this.conversationsRefresh$.next();
  }
}
