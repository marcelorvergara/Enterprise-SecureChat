import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, Subject } from 'rxjs';

export interface ChatRequest {
  message: string;
  conversationId?: string;
}

export interface SourceCitation {
  sourceFile: string;
  subjectPath: string;
  pageNumber?: number;
  sheetName?: string;
  score: number;
}

export interface ChatResponse {
  answer: string;
  conversationId: string;
  sources: SourceCitation[];
  fgaApplied: boolean;
  dlpEntitiesRedacted: number;
}

export interface Conversation {
  id: string;
  createdAt: string;
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
  private readonly conversationsRefresh$ = new Subject<void>();
  readonly refresh$ = this.conversationsRefresh$.asObservable();

  sendMessage(request: ChatRequest): Observable<ChatResponse> {
    return this.http.post<ChatResponse>('/api/chat', request);
  }

  getConversations(): Observable<Conversation[]> {
    return this.http.get<Conversation[]>('/api/conversations');
  }

  getMessages(conversationId: string): Observable<Message[]> {
    return this.http.get<Message[]>(`/api/conversations/${conversationId}/messages`);
  }

  notifyConversationCreated(): void {
    this.conversationsRefresh$.next();
  }
}
