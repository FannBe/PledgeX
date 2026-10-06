// End-to-end run of the live devnet program, with real transactions. Three pledges run
// side by side (2 days x 60 s each, one session key):
//   steps  : refused below target, clocked day 0, refused twice/stranger/early settle
//   wake   : 20 s window at the START of the day — day 0 in time, day 1 late and refused
//   screen : 20 s window at the END of the day — too early refused, over the limit refused
// then a third party settles all three (half refunded, half burned, accounts closed) and
// the owner's Profile adds up. Usage: node cli/e2e-devnet.mjs [payer-keypair.json]
import { Keypair, LAMPORTS_PER_SOL, SystemProgram } from "@solana/web3.js";
import { getMint, createMint, getOrCreateAssociatedTokenAccount, mintTo, createAssociatedTokenAccountIdempotentInstruction, createTransferCheckedInstruction, getAssociatedTokenAddressSync } from "@solana/spl-token";
import { ComputeBudgetProgram } from "@solana/web3.js";
import {
  connection, loadKeypair, faucetIxs, createCommitmentIx, clockInIx, settleIx, commitmentPda,
  vaultPda, profilePda, ata, decodeCommitment, decodeProfile, send, chainNow, UNIT, SKR_MINT, KIND,
  claimBadgeIx, badgeMintPda, badgeAccount, TOKEN_2022,
} from "./lib.mjs";

const conn = connection();
const payer = loadKeypair(process.argv[2] ?? new URL("../keys/deployer.json", import.meta.url));
const user = Keypair.generate();
const session = Keypair.generate();
const stranger = Keypair.generate();
let failures = 0;
const ok = (cond, msg) => { console.log(`${cond ? "PASS" : "FAIL"}  ${msg}`); if (!cond) failures++; };
const skr = async (owner) => BigInt((await conn.getTokenAccountBalance(ata(owner))).value.amount);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function expectFail(label, code, fn) {
  try { await fn(); ok(false, `${label} should have failed`); }
  catch (e) { const s = String(e?.logs?.join("\n") ?? e?.message ?? e); ok(s.includes(code), `${label} refused (${code})`); }
}
async function waitUntil(t) { while ((await chainNow(conn)) < t) await sleep(2000); }
const clock = (c, day, value, signer = session) => send(conn, [clockInIx({ signer: signer.publicKey, commitment: c, dayIndex: day, steps: value })], [signer]);

console.log("user", user.publicKey.toBase58());
await send(conn, [SystemProgram.transfer({ fromPubkey: payer.publicKey, toPubkey: user.publicKey, lamports: 0.06 * LAMPORTS_PER_SOL }),
  SystemProgram.transfer({ fromPubkey: payer.publicKey, toPubkey: stranger.publicKey, lamports: 0.01 * LAMPORTS_PER_SOL })], [payer]);
ok(!!(await send(conn, faucetIxs(user.publicKey), [user])) && (await skr(user.publicKey)) === 10_000n * UNIT, "faucet minted 10,000 test SKR");

const now = await chainNow(conn);
await expectFail("a start three days ahead", "InvalidSchedule", () => send(conn, [createCommitmentIx({
  user: user.publicKey, session: session.publicKey, id: 1n, targetSteps: 1, totalDays: 1, daySec: 60, amount: UNIT, startAt: now + 3 * 86400,
})], [user]));

// Stakes are test SKR only: a home-made token cannot build a record.
const fake = await createMint(conn, payer, payer.publicKey, null, 9);
const fakeAta = await getOrCreateAssociatedTokenAccount(conn, payer, fake, user.publicKey);
await mintTo(conn, payer, fake, fakeAta.address, payer, 1_000n * UNIT);
{
  const ix = createCommitmentIx({ user: user.publicKey, session: session.publicKey, id: 7n, targetSteps: 1, totalDays: 1, daySec: 60, amount: UNIT });
  ix.keys[5].pubkey = fakeAta.address; ix.keys[6].pubkey = fake; // user token account, mint
  await expectFail("a stake in another token", "ConstraintAddress", () => send(conn, [ix], [user]));
}

const base = BigInt(Date.now());
const stake = 1_000n * UNIT;
const specs = [
  { name: "steps", id: base, kind: KIND.STEPS, targetSteps: 1000, windowSec: 0 },
  { name: "wake", id: base + 1n, kind: KIND.WAKE, targetSteps: 0, windowSec: 20 },
  { name: "screen", id: base + 2n, kind: KIND.SCREEN, targetSteps: 60, windowSec: 20 },
];
const ixs = specs.map((p) => createCommitmentIx({ user: user.publicKey, session: session.publicKey, id: p.id, targetSteps: p.targetSteps,
  totalDays: 2, daySec: 60, amount: stake, kind: p.kind, windowSec: p.windowSec }));
ixs.push(SystemProgram.transfer({ fromPubkey: user.publicKey, toPubkey: session.publicKey, lamports: 0.01 * LAMPORTS_PER_SOL }));
console.log("create  " + await send(conn, ixs, [user]));
const [steps, wake, screen] = specs.map((p) => commitmentPda(user.publicKey, p.id));
const decoded = await Promise.all([steps, wake, screen].map(async (a) => decodeCommitment((await conn.getAccountInfo(a)).data)));
ok(decoded.map((c) => c.kind).join() === "0,2,1" && decoded[1].windowSec === 20, "three pledges created with their kinds and windows");
ok((await skr(user.publicKey)) === 7_000n * UNIT, "3 x 1,000 staked");
const start = decoded[0].start;

// steps
await expectFail("steps below target", "TargetNotMet", () => clock(steps, 0, 999));
ok(!!(await clock(steps, 0, 1234)), "steps: day 0 clocked in by the session key");
await expectFail("steps: second clock-in for day 0", "DayAlreadyClockedIn", () => clock(steps, 0, 1234));
await expectFail("steps: stranger clock-in", "UnauthorizedClockIn", () => clock(steps, 0, 1234, stranger));
// wake: in time on day 0
ok(!!(await clock(wake, 0, 0)), "wake: day 0 clocked in inside the first 20 s");
// screen: too early on day 0
await expectFail("screen: clock-in before the last 20 s", "InvalidDayWindow", () => clock(screen, 0, 30));
await expectFail("settle before the end", "CommitmentNotEnded", () =>
  send(conn, [settleIx({ caller: stranger.publicKey, user: user.publicKey, commitment: steps })], [stranger]));
await waitUntil(start + 42);
await expectFail("screen: 90 minutes against a 60 limit", "LimitExceeded", () => clock(screen, 0, 90));
ok(!!(await clock(screen, 0, 30)), "screen: day 0 clocked in at 30 minutes, inside the last 20 s");
// wake: late on day 1
await waitUntil(start + 60 + 25);
await expectFail("wake: day 1 after the 20 s window", "InvalidDayWindow", () => clock(wake, 1, 0));

await waitUntil(start + 122);
const supplyBefore = (await getMint(conn, SKR_MINT)).supply;
for (const [name, c] of [["steps", steps], ["wake", wake], ["screen", screen]]) {
  console.log(`settle ${name}  ` + await send(conn, [settleIx({ caller: stranger.publicKey, user: user.publicKey, commitment: c })], [stranger]));
}
ok((await skr(user.publicKey)) === 8_500n * UNIT, "settled by a third party: 3 x 500 refunded");
ok(supplyBefore - (await getMint(conn, SKR_MINT)).supply === 1_500n * UNIT, "missed days burned: supply fell by 1,500");
const closed = await Promise.all([steps, wake, screen].flatMap((c) => [c, vaultPda(c)]).map((a) => conn.getAccountInfo(a)));
ok(closed.every((a) => a === null), "all commitments and vaults closed");

const p = decodeProfile((await conn.getAccountInfo(profilePda(user.publicKey))).data);
ok(p.started === 3 && p.settled === 3 && p.kept === 3 && p.missed === 3,
  `profile: started ${p.started}, settled ${p.settled}, kept ${p.kept}, missed ${p.missed}`);
ok(p.realDaysKept === 0 && p.bestStreak === 0 && p.perfect === 0,
  "demo days (60 s) move the money but earn no rank or badges");
ok(p.returned === 1_500n * UNIT && p.burned === 1_500n * UNIT && p.staked === 3_000n * UNIT, "profile totals: staked 3,000, returned 1,500, burned 1,500");

// Soulbound badge: "First Pledge" is earned (3 pledges started); minted once, supply 1,
// no mint authority left, and it cannot leave the wallet. An unearned one is refused.
const cu = ComputeBudgetProgram.setComputeUnitLimit({ units: 400_000 });
const sigBadge = await send(conn, [cu, claimBadgeIx(user.publicKey, 0)], [user]);
const badgeMint = await getMint(conn, badgeMintPda(user.publicKey, 0), "confirmed", TOKEN_2022);
const held = (await conn.getTokenAccountBalance(badgeAccount(user.publicKey, 0))).value.amount;
ok(held === "1" && badgeMint.supply === 1n && badgeMint.mintAuthority === null && badgeMint.decimals === 0,
  `badge NFT minted: supply 1, no mint authority left  ${sigBadge}`);
await expectFail("claiming the same badge twice", "already in use", () => send(conn, [cu, claimBadgeIx(user.publicKey, 0)], [user]));
await expectFail("an unearned badge (needs a perfect real pledge)", "BadgeNotEarned", () => send(conn, [cu, claimBadgeIx(user.publicKey, 1)], [user]));
const dest = getAssociatedTokenAddressSync(badgeMintPda(user.publicKey, 0), stranger.publicKey, false, TOKEN_2022);
await expectFail("transferring the badge", "0x25", () => send(conn, [
  createAssociatedTokenAccountIdempotentInstruction(user.publicKey, dest, stranger.publicKey, badgeMintPda(user.publicKey, 0), TOKEN_2022),
  createTransferCheckedInstruction(badgeAccount(user.publicKey, 0), badgeMintPda(user.publicKey, 0), dest, user.publicKey, 1, 0, [], TOKEN_2022),
], [user]));

await expectFail("faucet for a wallet holding 8,500", "FaucetBalanceTooHigh", () => send(conn, faucetIxs(user.publicKey), [user]));

console.log(failures ? `\n${failures} FAILED` : "\nALL PASS");
process.exit(failures ? 1 : 0);
