import { Component, inject, OnInit, OnDestroy } from '@angular/core';
import { RouterOutlet, RouterLink, RouterLinkActive } from '@angular/router';
import { Subscription } from 'rxjs';
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
    RouterLinkActive,
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
export class AppComponent implements OnInit, OnDestroy {
  private readonly chatService = inject(ChatService);
  private refreshSub?: Subscription;

  conversations: Conversation[] = [];
  username = keycloak.tokenParsed?.['preferred_username'] ?? 'User';

  get primaryRole(): string {
    const SYSTEM = new Set(['offline_access', 'uma_authorization']);
    const roles = (keycloak.realmAccess?.roles ?? [])
      .filter(r => !SYSTEM.has(r) && !r.startsWith('default-roles-'));
    const specific = roles.filter(r => r !== 'employee');
    return specific[0] ?? roles[0] ?? '';
  }

  ngOnInit(): void {
    console.log('tokenParsed', keycloak.tokenParsed);
    this.username =
      keycloak.tokenParsed?.['preferred_username'] ??
      keycloak.tokenParsed?.['name'] ??
      keycloak.tokenParsed?.['email'] ??
      'User';
    this.loadConversations();
    this.refreshSub = this.chatService.refresh$.subscribe(() => this.loadConversations());
  }

  ngOnDestroy(): void {
    this.refreshSub?.unsubscribe();
  }

  private loadConversations(): void {
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
