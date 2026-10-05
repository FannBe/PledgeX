// End-to-end run of the live devnet program, with real transactions:
// faucet -> create (2 days x 60 s, with a session key) -> clock in day 0 with the session
// key -> refused double clock-in / stranger / early settle -> wait -> settle by a third
// party -> 50% refunded, 50% burned, accounts closed -> faucet refuses a funded wallet.
// Usage: node cli/e2e-devnet.mjs [payer-keypair.json]   (payer only funds gas)
import { Keypair, LAMPORTS_PER_SOL, SystemProgram } from "@solana/web3.js";
import { getMint } from "@solana/spl-token";
import {
  connection, loadKeypair, faucetIxs, createCommitmentIx, clockInIx, settleIx, commitmentPda,
  vaultPda, ata, decodeCommitment, send, chainNow, UNIT, SKR_MINT,
} from "./lib.mjs";

const conn = connection();
const payer = loadKeypair(process.argv[2] ?? new URL("../keys/deployer.json", import.meta.url));
const user = Keypair.generate();
const session = Keypair.generate();
const stranger = Keypair.generate();
let failures = 0;
const ok = (cond, msg) => { console.log(`${cond ? "PASS" : "FAIL"}  ${msg}`); if (!cond) failures++; };
const skr = async (owner) => BigInt((await conn.getTokenAccountBalance(ata(owner))).value.amount);
async function expectFail(label, code, fn) {
  try { await fn(); ok(false, `${label} should have failed`); }
  catch (e) { const s = String(e?.logs?.join("\n") ?? e?.message ?? e); ok(s.includes(code), `${label} refused (${code})`); }
}

console.log("user", user.publicKey.toBase58());
await send(conn, [SystemProgram.transfer({ fromPubkey: payer.publicKey, toPubkey: user.publicKey, lamports: 0.05 * LAMPORTS_PER_SOL }),
  SystemProgram.transfer({ fromPubkey: payer.publicKey, toPubkey: stranger.publicKey, lamports: 0.01 * LAMPORTS_PER_SOL })], [payer]);

const sigFaucet = await send(conn, faucetIxs(user.publicKey), [user]);
ok((await skr(user.publicKey)) === 10_000n * UNIT, `faucet minted 10,000 test SKR  ${sigFaucet}`);

const id = BigInt(Date.now());
const stake = 3_000n * UNIT;
const commitment = commitmentPda(user.publicKey, id);
const sigCreate = await send(conn, [
  createCommitmentIx({ user: user.publicKey, session: session.publicKey, id, targetSteps: 1000, totalDays: 2, daySec: 60, amount: stake }),
  // The session key pays its own clock-in fees, so it gets a little SOL with the stake.
  SystemProgram.transfer({ fromPubkey: user.publicKey, toPubkey: session.publicKey, lamports: 0.005 * LAMPORTS_PER_SOL }),
], [user]);
let c = decodeCommitment((await conn.getAccountInfo(commitment)).data);
ok(c.totalAmount === stake && c.totalDays === 2 && c.clockInAuthority.equals(session.publicKey) && c.id === id, `commitment created  ${sigCreate}`);
ok((await skr(user.publicKey)) === 7_000n * UNIT, "stake left the wallet");

await expectFail("steps below target", "TargetNotMet", () =>
  send(conn, [clockInIx({ signer: session.publicKey, commitment, dayIndex: 0, steps: 999 })], [session]));
const sigClock = await send(conn, [clockInIx({ signer: session.publicKey, commitment, dayIndex: 0, steps: 1234 })], [session]);
ok(true, `session key clocked in day 0 without the wallet  ${sigClock}`);
await expectFail("second clock-in for day 0", "DayAlreadyClockedIn", () =>
  send(conn, [clockInIx({ signer: session.publicKey, commitment, dayIndex: 0, steps: 1234 })], [session]));
await expectFail("clock-in for day 1 during day 0", "InvalidDayWindow", () =>
  send(conn, [clockInIx({ signer: session.publicKey, commitment, dayIndex: 1, steps: 1234 })], [session]));
await expectFail("stranger clock-in", "UnauthorizedClockIn", () =>
  send(conn, [clockInIx({ signer: stranger.publicKey, commitment, dayIndex: 0, steps: 1234 })], [stranger]));
await expectFail("settle before the end", "CommitmentNotEnded", () =>
  send(conn, [settleIx({ caller: stranger.publicKey, user: user.publicKey, commitment })], [stranger]));

c = decodeCommitment((await conn.getAccountInfo(commitment)).data);
const end = c.start + c.totalDays * c.daySec;
for (let now = await chainNow(conn); now <= end + 2; now = await chainNow(conn)) {
  process.stdout.write(`\rwaiting for chain time ${end - now}s `);
  await new Promise((r) => setTimeout(r, 5000));
}
console.log();

const supplyBefore = (await getMint(conn, SKR_MINT)).supply;
const lamportsBefore = await conn.getBalance(user.publicKey);
const sigSettle = await send(conn, [settleIx({ caller: stranger.publicKey, user: user.publicKey, commitment })], [stranger]);
ok((await skr(user.publicKey)) === 8_500n * UNIT, `settled by a third party: 1 of 2 days -> 1,500 refunded  ${sigSettle}`);
ok(supplyBefore - (await getMint(conn, SKR_MINT)).supply === 1_500n * UNIT, "missed day burned: supply fell by 1,500");
ok((await conn.getAccountInfo(commitment)) === null && (await conn.getAccountInfo(vaultPda(commitment))) === null, "commitment and vault closed");
ok((await conn.getBalance(user.publicKey)) > lamportsBefore, "rent of both accounts went back to the owner");

await expectFail("faucet for a wallet holding 8,500", "FaucetBalanceTooHigh", () => send(conn, faucetIxs(user.publicKey), [user]));

console.log(failures ? `\n${failures} FAILED` : "\nALL PASS");
process.exit(failures ? 1 : 0);
