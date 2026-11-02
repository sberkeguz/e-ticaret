FROM node:22-alpine AS build
WORKDIR /app

COPY admin-panel/package.json admin-panel/package-lock.json ./
RUN npm ci

COPY admin-panel/ ./
RUN npm run build

FROM nginx:alpine
COPY admin-panel/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
