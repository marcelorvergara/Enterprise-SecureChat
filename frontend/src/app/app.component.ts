import { Component, inject, OnInit, OnDestroy } from '@angular/core';
import { RouterOutlet, RouterLink, RouterLinkActive } from '@angular/router';
import { Subscription } from 'rxjs';
import { filter, take } from 'rxjs/operators';
import { DatePipe } from '@angular/common';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatListModule } from '@angular/material/list';
import { MatDividerModule } from '@angular/material/divider';
import { MatMenuModule } from '@angular/material/menu';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AuthService } from '@auth0/auth0-angular';
import { ChatService, Conversation } from './core/services/chat.service';
import { ThemeService } from './core/services/theme.service';

const ROLES_CLAIM = 'https://enpsecurechat.com/roles';

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
  private readonly auth = inject(AuthService);
  readonly themeService = inject(ThemeService);
  private readonly subs = new Subscription();

  conversations: Conversation[] = [];
  username = 'User';
  isAdmin = false;
  primaryRole = '';

  ngOnInit(): void {
    this.subs.add(
      this.auth.user$.subscribe(user => {
        if (!user) return;
        this.username = user['nickname'] ?? user['name'] ?? user['email'] ?? 'User';
        const roles: string[] = user[ROLES_CLAIM] ?? [];
        this.isAdmin = roles.includes('admin');
        const specific = roles.filter(r => r !== 'employee');
        this.primaryRole = specific[0] ?? roles[0] ?? '';
      }),
    );
    // Wait for Auth0 to confirm authentication before the first API call.
    // Calling loadConversations() unconditionally on init races against the
    // Auth0 callback processing — getAccessTokenSilently() throws and the
    // interceptor silently drops the request (returns EMPTY).
    this.subs.add(
      this.auth.isAuthenticated$.pipe(filter(Boolean), take(1))
        .subscribe(() => this.loadConversations()),
    );
    this.subs.add(this.chatService.refresh$.subscribe(() => this.loadConversations()));
  }

  ngOnDestroy(): void {
    this.subs.unsubscribe();
  }

  private loadConversations(): void {
    this.chatService.getConversations().subscribe({
      next: convs => (this.conversations = convs),
      error: () => {},
    });
  }

  logout(): void {
    this.auth.logout({ logoutParams: { returnTo: window.location.origin } });
  }
}
