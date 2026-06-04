import { Component, inject, OnInit } from '@angular/core';
import { RouterOutlet, RouterLink } from '@angular/router';
import { DatePipe } from '@angular/common';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatListModule } from '@angular/material/list';
import { MatDividerModule } from '@angular/material/divider';
import { MatMenuModule } from '@angular/material/menu';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ChatService, Conversation } from './core/services/chat.service';
import { keycloak } from './core/auth/keycloak.init';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    RouterOutlet,
    RouterLink,
    DatePipe,
    MatToolbarModule,
    MatSidenavModule,
    MatIconModule,
    MatButtonModule,
    MatListModule,
    MatDividerModule,
    MatMenuModule,
    MatTooltipModule,
  ],
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.css'],
})
export class AppComponent implements OnInit {
  private readonly chatService = inject(ChatService);

  conversations: Conversation[] = [];
  readonly username = keycloak.tokenParsed?.['preferred_username'] ?? 'User';

  ngOnInit(): void {
    this.chatService.getConversations().subscribe({
      next: convs => (this.conversations = convs),
      error: () => {},
    });
  }

  get isAdmin(): boolean {
    return keycloak.realmAccess?.roles?.includes('admin') ?? false;
  }

  logout(): void {
    keycloak.logout({ redirectUri: window.location.origin });
  }
}
