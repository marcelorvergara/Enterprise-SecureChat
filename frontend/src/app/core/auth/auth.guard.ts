import { CanActivateFn, Router } from '@angular/router';
import { inject } from '@angular/core';
import { AuthService } from '@auth0/auth0-angular';
import { map } from 'rxjs/operators';

const ROLES_CLAIM = 'https://enpsecurechat.com/roles';

export const adminGuard: CanActivateFn = () => {
  const router = inject(Router);
  const auth = inject(AuthService);
  return auth.user$.pipe(
    map(user => {
      const roles: string[] = user?.[ROLES_CLAIM] ?? [];
      const isAdmin = roles.includes('admin');
      if (!isAdmin) router.navigate(['/']);
      return isAdmin;
    }),
  );
};
