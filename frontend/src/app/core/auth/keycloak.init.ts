import Keycloak from 'keycloak-js';
import { APP_INITIALIZER, Provider } from '@angular/core';
import { environment } from '../../../environments/environment';

// Module-level singleton — shared by the interceptor, guard, and app shell.
export const keycloak = new Keycloak({
  url: environment.keycloakUrl,
  realm: environment.keycloakRealm,
  clientId: environment.keycloakClientId,
});

function initKeycloak(): () => Promise<boolean> {
  return () =>
    keycloak.init({
      onLoad: 'login-required',
      checkLoginIframe: false,
    });
}

export const KEYCLOAK_INIT_PROVIDER: Provider = {
  provide: APP_INITIALIZER,
  useFactory: initKeycloak,
  multi: true,
};
