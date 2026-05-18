FROM node:22-alpine

WORKDIR /app
ENV NODE_ENV=production

COPY deploy/backend/package*.json ./
RUN npm ci --omit=dev

COPY deploy/backend/src ./src
COPY deploy/backend/public ./public

EXPOSE 8090
CMD ["node", "src/server.js"]
