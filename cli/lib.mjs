// Hand-built instructions for the PledgeX program, the same bytes the Android app sends.
// Kept dependency-light on purpose: if this file and the app disagree, the e2e catches it.
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import {
  Connection, Keypair, PublicKey, SystemProgram, Transaction, TransactionInstruction,
  SYSVAR_RENT_PUBKEY, sendAndConfirmTransaction,
} from "@solana/web3.js";
import {
  TOKEN_PROGRAM_ID, ASSOCIATED_TOKEN_PROGRAM_ID, getAssociatedTokenAddressSync,
  createAssociatedTokenAccountIdempotentInstruction,
} from "@solana/spl-token";

export const PROGRAM_ID = new PublicKey("68c1eNdHAfNWJhCWhtqumiqzYyFcDNLkfgwKwdFwFRcd");
export const SKR_MINT = new PublicKey("F4L7W4qgFAuU2iyg5ePBENXTHeZyTPor4tJMfhUHqfgQ");
export const DECIMALS = 9;
export const UNIT = 10n ** 9n;

export function rpcUrl() {
  if (process.env.RPC_URL) return process.env.RPC_URL;
  try { return readFileSync(new URL("../keys/rpc-url.txt", import.meta.url), "utf8").trim(); } catch { }
  return "https://api.devnet.solana.com";
}
export const connection = () => new Connection(rpcUrl(), "confirmed");

export function loadKeypair(path) {
  return Keypair.fromSecretKey(Uint8Array.from(JSON.parse(readFileSync(path, "utf8"))));
}

const disc = (name) => createHash("sha256").update(`global:${name}`).digest().subarray(0, 8);
export const ACCOUNT_DISC = createHash("sha256").update("account:Commitment").digest().subarray(0, 8);

const u64 = (v) => { const b = Buffer.alloc(8); b.writeBigUInt64LE(BigInt(v)); return b; };
const u32 = (v) => { const b = Buffer.alloc(4); b.writeUInt32LE(v); return b; };
const u8 = (v) => Buffer.from([v]);

export const commitmentPda = (user, id) =>
  PublicKey.findProgramAddressSync([Buffer.from("commitment"), user.toBuffer(), u64(id)], PROGRAM_ID)[0];
export const vaultPda = (commitment) =>
  PublicKey.findProgramAddressSync([Buffer.from("vault"), commitment.toBuffer()], PROGRAM_ID)[0];
export const profilePda = (user) =>
  PublicKey.findProgramAddressSync([Buffer.from("profile"), user.toBuffer()], PROGRAM_ID)[0];
export const KIND = { STEPS: 0, SCREEN: 1, WAKE: 2 };
const i64 = (v) => { const b = Buffer.alloc(8); b.writeBigInt64LE(BigInt(v)); return b; };
export const TOKEN_2022 = new PublicKey("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb");
export const badgeMintPda = (user, badge) =>
  PublicKey.findProgramAddressSync([Buffer.from("badge"), user.toBuffer(), Buffer.from([badge])], PROGRAM_ID)[0];
export const badgeAuthorityPda = () => PublicKey.findProgramAddressSync([Buffer.from("badge_auth")], PROGRAM_ID)[0];
export const badgeAccount = (user, badge) => getAssociatedTokenAddressSync(badgeMintPda(user, badge), user, false, TOKEN_2022);
export const faucetPda = () => PublicKey.findProgramAddressSync([Buffer.from("faucet")], PROGRAM_ID)[0];
export const ata = (owner) => getAssociatedTokenAddressSync(SKR_MINT, owner);

const ix = (keys, data) => new TransactionInstruction({ programId: PROGRAM_ID, keys, data });
const w = (pubkey, isSigner = false) => ({ pubkey, isSigner, isWritable: true });
const r = (pubkey, isSigner = false) => ({ pubkey, isSigner, isWritable: false });

export function faucetIxs(user) {
  return [
    createAssociatedTokenAccountIdempotentInstruction(user, ata(user), user, SKR_MINT),
    ix([r(user, true), w(SKR_MINT), r(faucetPda()), w(ata(user)), r(TOKEN_PROGRAM_ID)], disc("faucet")),
  ];
}

export function createCommitmentIx({ user, session, id, targetSteps, totalDays, daySec, amount, kind = 0, startAt = 0, windowSec = 0 }) {
  const commitment = commitmentPda(user, id);
  return ix(
    [w(user, true), r(session ?? SystemProgram.programId), w(commitment), w(vaultPda(commitment)), w(profilePda(user)),
      w(ata(user)), r(SKR_MINT), r(TOKEN_PROGRAM_ID), r(SystemProgram.programId), r(SYSVAR_RENT_PUBKEY)],
    Buffer.concat([disc("create_commitment"), u64(id), u32(targetSteps), u8(totalDays), u64(daySec), u64(amount),
      u8(kind), i64(startAt), u32(windowSec)]),
  );
}

/** Mint the caller's soulbound badge NFT (Token-2022, non-transferable, supply 1). */
export function claimBadgeIx(user, badge) {
  return ix(
    [w(user, true), r(profilePda(user)), w(badgeMintPda(user, badge)), r(badgeAuthorityPda()), w(badgeAccount(user, badge)),
      r(TOKEN_2022), r(ASSOCIATED_TOKEN_PROGRAM_ID), r(SystemProgram.programId)],
    Buffer.concat([disc("claim_badge"), u8(badge)]),
  );
}

export function clockInIx({ signer, commitment, dayIndex, steps }) {
  return ix([w(signer, true), w(commitment)], Buffer.concat([disc("clock_in"), u8(dayIndex), u32(steps)]));
}

export function settleIx({ caller, user, commitment }) {
  return ix(
    [w(caller, true), w(user), w(commitment), w(vaultPda(commitment)), w(ata(user)), w(SKR_MINT), w(profilePda(user)),
      r(TOKEN_PROGRAM_ID), r(SystemProgram.programId)],
    disc("settle"),
  );
}

/** Decode a Commitment account (layout of state.rs). */
export function decodeCommitment(data) {
  const b = Buffer.from(data);
  if (!b.subarray(0, 8).equals(ACCOUNT_DISC)) throw new Error("not a Commitment");
  let o = 8;
  const pk = () => { const p = new PublicKey(b.subarray(o, o + 32)); o += 32; return p; };
  const authority = pk(), clockInAuthority = pk(), tokenMint = pk(), vault = pk();
  const targetSteps = b.readUInt32LE(o); o += 4;
  const totalDays = b[o++], completedDays = b[o++];
  const daySec = Number(b.readBigUInt64LE(o)); o += 8;
  const start = Number(b.readBigInt64LE(o)); o += 8;
  const totalAmount = b.readBigUInt64LE(o); o += 8;
  const settled = b[o++] === 1;
  const bitmap = b.readBigUInt64LE(o); o += 8;
  o += 2;
  const id = b.readBigUInt64LE(o); o += 8;
  const kind = b[o++];
  const windowSec = b.readUInt32LE(o);
  return { authority, clockInAuthority, tokenMint, vault, targetSteps, totalDays, completedDays, daySec, start, totalAmount, settled, bitmap, id, kind, windowSec };
}

export const PROFILE_DISC = createHash("sha256").update("account:Profile").digest().subarray(0, 8);
/** Decode a Profile account (layout of state.rs). */
export function decodeProfile(data) {
  const b = Buffer.from(data);
  if (!b.subarray(0, 8).equals(PROFILE_DISC)) throw new Error("not a Profile");
  let o = 40;
  const n = () => { const v = b.readUInt32LE(o); o += 4; return v; };
  const big = () => { const v = b.readBigUInt64LE(o); o += 8; return v; };
  const started = n(), settled = n(), perfect = n(), kept = n(), missed = n();
  const staked = big(), returned = big(), burned = big();
  return { authority: new PublicKey(b.subarray(8, 40)), started, settled, perfect, kept, missed, staked, returned, burned, bestStreak: b[o], perfectKinds: b[o + 1], realDaysKept: b.readUInt32LE(o + 3) };
}

export async function send(conn, ixs, signers, feePayer = signers[0]) {
  const tx = new Transaction().add(...ixs);
  tx.feePayer = feePayer.publicKey;
  return sendAndConfirmTransaction(conn, tx, signers, { commitment: "confirmed", skipPreflight: false });
}

/** Chain time, never the PC clock (this machine's clock has drifted minutes). */
export async function chainNow(conn) {
  const slot = await conn.getSlot("confirmed");
  return conn.getBlockTime(slot);
}

export { ASSOCIATED_TOKEN_PROGRAM_ID };
