import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { AuthService } from '@auth0/auth0-angular';
import { switchMap, catchError } from 'rxjs/operators';
import { EMPTY } from 'rxjs';

export const authInterceptorFn: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith('/api')) {
    return next(req);
  }

  const auth = inject(AuthService);
  return auth.getAccessTokenSilently().pipe(
    switchMap(token => next(req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }))),
    catchError((err: unknown) => {
      const code = (err as { error?: string })?.error;
      if (code === 'login_required' || code === 'consent_required') {
        auth.loginWithRedirect();
      }
      return EMPTY;
    }),
  );
};
