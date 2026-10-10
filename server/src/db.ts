import pg, { type PoolClient } from "pg";
export type Db = pg.Pool;
export function database(url: string): Db {
  return new pg.Pool({
    connectionString: url,
    max: 8,
    connectionTimeoutMillis: 3000,
    statement_timeout: 15000,
  });
}
export async function transaction<T>(
  db: Db,
  body: (client: PoolClient) => Promise<T>,
): Promise<T> {
  const c = await db.connect();
  let broken = false;
  try {
    await c.query("BEGIN");
    const value = await body(c);
    await c.query("COMMIT");
    return value;
  } catch (error) {
    try {
      await c.query("ROLLBACK");
    } catch {
      broken = true;
    }
    throw error;
  } finally {
    c.release(broken);
  }
}
