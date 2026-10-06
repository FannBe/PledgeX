// Settles every pledge that ended more than an hour ago and is still open, so no stake
// waits forever on an owner who forgot. settle is permissionless: the crank only pays the
// fee (and a Profile's rent for a pledge made before profiles existed); the refund and
// both accounts' rent go to the pledge's owner.
//
// Usage: node cli/crank.mjs            (key: $CRANK_KEYPAIR as a JSON array, else keys/crank.json)
//        node cli/crank.mjs --dry-run  (list what would be settled)
import { Keypair, PublicKey } from "@solana/web3.js";
import { readFileSync } from "node:fs";
import { connection, decodeCommitment, settleIx, send, chainNow, PROGRAM_ID, loadKeypair } from "./lib.mjs";

const GRACE_SEC = 3600;
const COMMITMENT_LEN = 209;
const dry = process.argv.includes("--dry-run");

const conn = connection();
const crank = process.env.CRANK_KEYPAIR
  ? Keypair.fromSecretKey(Uint8Array.from(JSON.parse(process.env.CRANK_KEYPAIR)))
  : loadKeypair(new URL("../keys/crank.json", import.meta.url));

const now = await chainNow(conn);
const accounts = await conn.getProgramAccounts(PROGRAM_ID, { filters: [{ dataSize: COMMITMENT_LEN }] });
const due = accounts
  .map(({ pubkey, account }) => ({ address: pubkey, c: decodeCommitment(account.data) }))
  .filter(({ c }) => !c.settled && now >= c.start + c.totalDays * c.daySec + GRACE_SEC);

console.log(`chain time ${now}: ${accounts.length} open pledges, ${due.length} due for settlement`);
let settled = 0;
for (const { address, c } of due) {
  const label = `${address.toBase58()} (owner ${c.authority.toBase58().slice(0, 6)}…, ${c.completedDays}/${c.totalDays} kept)`;
  if (dry) { console.log("would settle", label); continue; }
  try {
    const sig = await send(conn, [settleIx({ caller: crank.publicKey, user: c.authority, commitment: address })], [crank]);
    console.log("settled", label, sig);
    settled++;
  } catch (e) {
    // One bad pledge must not stop the rest; the next run retries it.
    console.log("FAILED", label, String(e?.message ?? e).split("\n")[0]);
  }
}
const balance = await conn.getBalance(crank.publicKey);
console.log(`settled ${settled}/${due.length}; crank ${crank.publicKey.toBase58()} holds ${(balance / 1e9).toFixed(4)} SOL`);
if (balance < 50_000_000) console.log("::warning::crank balance below 0.05 SOL, top it up");
