import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

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

@Injectable({ providedIn: 'root' })
export class ChatService {
  private readonly http = inject(HttpClient);

  sendMessage(request: ChatRequest): Observable<ChatResponse> {
    return this.http.post<ChatResponse>('/api/chat', request);
  }

  getConversations(): Observable<Conversation[]> {
    return this.http.get<Conversation[]>('/api/conversations');
  }
}
