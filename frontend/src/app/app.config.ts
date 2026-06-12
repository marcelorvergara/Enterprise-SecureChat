import { Chart, registerables } from 'chart.js';
Chart.register(...registerables);

import { ApplicationConfig } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideAnimations } from '@angular/platform-browser/animations';
import { provideAuth0 } from '@auth0/auth0-angular';
import { routes } from './app.routes';
import { authInterceptorFn } from './core/auth/auth.interceptor';
import { environment } from '../environments/environment';

export const appConfig: ApplicationConfig = {
  providers: [
    provideRouter(routes),
    provideHttpClient(withInterceptors([authInterceptorFn])),
    provideAnimations(),
    provideAuth0({
      domain: environment.auth0Domain,
      clientId: environment.auth0ClientId,
      authorizationParams: {
        redirect_uri: window.location.origin,
        audience: environment.auth0Audience,
      },
      useRefreshTokens: true,
      cacheLocation: 'localstorage',
    }),
  ],
};
