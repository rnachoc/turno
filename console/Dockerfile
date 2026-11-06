# Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
FROM node:26.12.1-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY angular.json tsconfig.json tsconfig.app.json ./
COPY public/ public/
COPY src/ src/
RUN npx ng build

FROM nginxinc/nginx-unprivileged:1.29.8-alpine
COPY nginx/default.conf /etc/nginx/conf.d/default.conf
COPY nginx/security-headers.conf /etc/nginx/security-headers.conf
COPY nginx/proxy.conf /etc/nginx/proxy.conf
COPY --from=build /app/dist/console/browser /usr/share/nginx/html
EXPOSE 8080
