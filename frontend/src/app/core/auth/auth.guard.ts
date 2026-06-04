import { CanActivateFn, Router } from '@angular/router';
import { inject } from '@angular/core';
import { keycloak } from './keycloak.init';

export const adminGuard: CanActivateFn = () => {
  const router = inject(Router);
  const isAdmin = keycloak.realmAccess?.roles?.includes('admin') ?? false;
  if (!isAdmin) {
    router.navigate(['/']);
  }
  return isAdmin;
};
