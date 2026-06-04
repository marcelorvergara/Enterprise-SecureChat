import { HttpInterceptorFn } from '@angular/common/http';
import { EMPTY, from } from 'rxjs';
import { catchError, switchMap } from 'rxjs/operators';
import { keycloak } from './keycloak.init';

export const authInterceptorFn: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith('/api')) {
    return next(req);
  }

  const attachToken = () => {
    const token = keycloak.token;
    if (!token) return next(req);
    return next(req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }));
  };

  // Proactively refresh token if it expires within 30 seconds — covers the
  // Claude API call which can take up to 60 s to complete.
  if (keycloak.isTokenExpired(30)) {
    return from(keycloak.updateToken(30)).pipe(
      catchError(() => {
        keycloak.login();
        return EMPTY;
      }),
      switchMap(() => attachToken()),
    );
  }

  return attachToken();
};
