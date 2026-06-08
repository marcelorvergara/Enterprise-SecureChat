import { Routes } from '@angular/router';
import { AuthGuard } from '@auth0/auth0-angular';
import { adminGuard } from './core/auth/auth.guard';

export const routes: Routes = [
  {
    path: '',
    canActivate: [AuthGuard],
    loadComponent: () =>
      import('./features/chat/chat.component').then(m => m.ChatComponent),
  },
  {
    path: 'c/:id',
    canActivate: [AuthGuard],
    loadComponent: () =>
      import('./features/chat/chat.component').then(m => m.ChatComponent),
  },
  {
    path: 'admin',
    loadComponent: () =>
      import('./features/admin/admin.component').then(m => m.AdminComponent),
    canActivate: [AuthGuard, adminGuard],
  },
  { path: '**', redirectTo: '' },
];
