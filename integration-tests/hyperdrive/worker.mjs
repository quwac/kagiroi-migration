import { mysqlContract, postgresContract } from "./build/compileSync/js/main/productionExecutable/kotlin/kagiroi-migration-integration-tests-hyperdrive.mjs";

export default {
  async fetch(request, environment) {
    if (request.method !== "POST") return new Response("Method Not Allowed", { status: 405 });
    try {
      const result = new URL(request.url).pathname === "/mysql"
        ? await mysqlContract(environment.HYPERDRIVE)
        : await postgresContract(environment.HYPERDRIVE);
      return Response.json({ ok: result === "ok" });
    } catch (error) {
      return Response.json({ ok: false, error: String(error) }, { status: 500 });
    }
  },
};
