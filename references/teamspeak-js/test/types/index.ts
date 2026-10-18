import { listChannels } from "@honeybbq/teamspeak";
import { Client, generateIdentitySync } from "@honeybbq/teamspeak/client";
import * as versions from "@honeybbq/teamspeak/versions";

export async function main(): Promise<void> {
  await using client = new Client(generateIdentitySync(8), "127.0.0.1:9987", "types", {});
  using stop = client.on("connected", () => {});
  void stop;
  await client.connect();
  console.log(await listChannels(client), Object.keys(versions).length);
}
