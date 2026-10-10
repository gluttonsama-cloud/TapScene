import { config } from "./config.js";
import { buildApp } from "./app.js";
const cfg = config();
const { app, worker } = await buildApp(cfg);
await app.listen({ host: cfg.host, port: cfg.port });
worker.start();
process.stdout.write(`TapScene local synthetic viewer: ${cfg.origin}\n`);
for (const signal of ["SIGINT", "SIGTERM"] as const)
  process.once(signal, () => {
    void app.close().then(() => process.exit(0));
  });
