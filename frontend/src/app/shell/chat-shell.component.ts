import { Component, inject, OnInit, OnDestroy } from '@angular/core';
import { Router, RouterOutlet, RouterLink, RouterLinkActive } from '@angular/router';
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
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { AuthService } from '@auth0/auth0-angular';
import { ChatService, Conversation } from '../core/services/chat.service';
import { ThemeService } from '../core/services/theme.service';
import { DeleteConversationDialogComponent } from '../shared/dialogs/delete-conversation-dialog.component';

const ROLES_CLAIM = 'https://enpsecurechat.com/roles';

@Component({
  selector: 'app-chat-shell',
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
    MatDialogModule,
    MatSnackBarModule,
  ],
  templateUrl: './chat-shell.component.html',
  styleUrls: ['./chat-shell.component.css'],
})
export class ChatShellComponent implements OnInit, OnDestroy {
  private readonly chatService = inject(ChatService);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly router = inject(Router);
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

  deleteConversation(event: MouseEvent, conv: Conversation): void {
    event.preventDefault();
    event.stopPropagation();
    const ref = this.dialog.open(DeleteConversationDialogComponent, { width: '320px' });
    ref.afterClosed().subscribe(confirmed => {
      if (!confirmed) return;
      this.chatService.deleteConversation(conv.id).subscribe({
        next: () => {
          this.conversations = this.conversations.filter(c => c.id !== conv.id);
          if (this.router.url.includes(conv.id)) {
            this.router.navigate(['/chat']);
          }
        },
        error: () => {
          this.snackBar.open('Failed to delete conversation. Please try again.', 'Dismiss', {
            duration: 4000,
          });
        },
      });
    });
  }

  logout(): void {
    this.auth.logout({ logoutParams: { returnTo: window.location.origin } });
  }
}
