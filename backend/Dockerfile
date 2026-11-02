# Ustad backend: tiny Node.js proxy, no dependencies.
FROM node:20-alpine
WORKDIR /app
COPY package.json server.js ./
ENV PORT=8787
EXPOSE 8787
USER node
CMD ["node", "server.js"]
