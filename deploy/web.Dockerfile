FROM node:24-alpine AS build

WORKDIR /workspace
ENV CI=true
RUN corepack enable
COPY workflow-agent-web/package.json workflow-agent-web/pnpm-lock.yaml workflow-agent-web/pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile
COPY workflow-agent-web/ ./
RUN pnpm build

FROM nginx:stable-alpine

COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist /usr/share/nginx/html
EXPOSE 80
