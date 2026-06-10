# ── Stage 1: Build ──────────────────────────────────────────────────────────
FROM node:20-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm install
COPY . .
RUN npm run build -- --configuration production

# ── Stage 2: Nginx runtime ───────────────────────────────────────────────────
FROM nginx:1.27-alpine
COPY --from=builder /app/dist/enterprise-securechat-frontend/browser /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
