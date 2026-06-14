# Frontend — Angular 17 (standalone) + Angular Material 17

## Dev Commands

```bash
npm install            # install dependencies (first time or after package.json changes)
npm start              # dev server on :4200, proxies /api/ to http://localhost:3000
npm run build          # production build → dist/.../browser/
```

The dev server proxies `/api/` to `http://localhost:3000` via `proxy.conf.json`. There is **no frontend Docker container in development** — the Angular dev server runs separately from the docker-compose stack.

## Key Files

```
src/app/
├── app.config.ts       provideAuth0(), Chart.register(...registerables), appConfig bootstrap
├── core/auth/
│   ├── auth.interceptor.ts   adds Authorization: Bearer JWT to all /api/ requests
│   └── auth.guard.ts         route guard; redirects to Auth0 if not authenticated
├── core/services/
│   ├── chat.service.ts                 POST /api/chat, POST /api/chat/verify, GET conversations
│   └── conversation-export.service.ts  exportMarkdown() + exportPdf() (no extra npm deps)
├── features/chat/
│   ├── chat.component.*               main chat UI, sidebar, message thread
│   ├── bu-upload-modal.component.*    file upload modal (bu-user / reserves roles only)
│   └── source-preview-dialog.*        Qdrant chunk preview dialog
├── features/admin/
│   └── admin.component.*              restriction CRUD + security heatmap charts (admin role only)
└── shared/pipes/
    └── safe-markdown.pipe.ts          marked → DOMPurify → SafeHtml (never bind LLM output as raw HTML)
```

## Auth0 Integration

Uses `@auth0/auth0-angular`. Configuration in `src/environments/environment.ts`:
- `domain`: Auth0 tenant domain
- `clientId`: SPA application client ID
- `authorizationParams.audience`: `api.enpsecurechat.com`

Token is cached in `localstorage` with `useRefreshTokens: true`. The interceptor attaches `Authorization: Bearer <token>` to all requests matching `/api/`.

## Charts (Admin Heatmap)

`ng2-charts@6` + `chart.js@4.4.0` installed with `--legacy-peer-deps`. `"skipLibCheck": true` is set in `tsconfig.json` to suppress ng2-charts v6 type declaration errors. `Chart.register(...registerables)` must remain in `app.config.ts` before `appConfig` is declared — moving it breaks the admin charts.

## Role-Gated UI Elements

| Element | Visible to |
|---------|-----------|
| `BuUploadModalComponent` (cloud_upload button) | `bu-user`, `reserves-management`, `reserves-coordination` |
| Admin panel route | `admin` only (route guard + `@PreAuthorize` on every backend endpoint) |
| Download/export button | Any authenticated user with ≥1 message in conversation |

## Production Deployment

The Angular build is deployed to **Firebase Hosting** — not Docker. Build artifacts go to `dist/.../browser/`. See [DEPLOYMENT.md](../DEPLOYMENT.md) for Firebase setup and the `frontend.yml` CI/CD workflow. The `/api/**` rewrite in `firebase.json` forwards requests to the `securechat-backend` Cloud Run service.
