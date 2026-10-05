<p align="center"><img src="docs/logo.png" width="140" alt="PledgeX logo"></p>

# PledgeX

**Stake on your daily steps. Walk, and it comes back. Skip a day, and that day's stake burns.**

PledgeX is an Android app and a Solana program. You lock test SKR for a number of days with a daily step goal. Each day your phone's step counter reaches the goal, you clock in on chain. After the last day the program returns the days you kept and **burns** the days you missed. Nobody receives a missed stake, including the developers.

Runs on **Solana devnet** with a test token. Site: <https://fannbe.github.io/PledgeX/> · APK: [releases](https://github.com/FannBe/PledgeX/releases)

| | |
|---|---|
| Program | [`68c1eNdHAfNWJhCWhtqumiqzYyFcDNLkfgwKwdFwFRcd`](https://explorer.solana.com/address/68c1eNdHAfNWJhCWhtqumiqzYyFcDNLkfgwKwdFwFRcd?cluster=devnet) |
| Test SKR mint | [`F4L7W4qgFAuU2iyg5ePBENXTHeZyTPor4tJMfhUHqfgQ`](https://explorer.solana.com/address/F4L7W4qgFAuU2iyg5ePBENXTHeZyTPor4tJMfhUHqfgQ?cluster=devnet) (9 decimals) |
| Mint authority | the program's `faucet` PDA `7Twns5ha3zR36upXJpSkUo6wsPRMS78byuJECimhkDHL`; no person holds a minting key |

## Try it in six minutes

1. Install the APK. Then either tap **Use a demo wallet** (a keypair kept on the phone), or tap **Connect wallet** for Phantom or Solflare. Phantom needs *Settings → Developer Settings → Testnet Mode*.
2. Tap **Get devnet SOL**, then **Get 10,000 test SKR**. The test SKR is minted by the program's own `faucet` instruction.
3. Create a pledge with **Day length: Demo (2 minutes)** and **3 days**.
4. Day 1: tap **Add 1,000 simulated steps** until you reach the goal, then **Clock in**. The clock-in has no wallet screen; the next section explains why. Skip day 2. Clock in on day 3.
5. After six minutes, tap **Settle**. Two thirds of the stake come back and one third is burned. Every action is listed under **On-chain activity** with a Solana Explorer link.

Simulated steps exist **only in demo pledges** and are labelled as simulated. A real pledge uses 24-hour days and the phone's hardware step counter.

## How it works

```
Phone                                         Solana program (Anchor)
─────                                         ───────────────────────
wallet (Phantom / demo key) ── create_commitment ─▶ Commitment PDA  [commitment, owner, id]
  stake + a little SOL for the session key          Vault PDA       [vault, commitment] holds the stake
session key (on the phone) ─── clock_in(day, steps) ─▶ sets bit `day` in the bitmap
anyone ─────────────────────── settle ─────────────▶ refund kept days → owner
                                                     burn missed days, close both accounts
wallet ─────────────────────── faucet ─────────────▶ mint 10,000 test SKR while balance < 5,000
```

- **Day windows use Solana time.** Day *i* is `[start + i·day, start + (i+1)·day)`, read from the Clock sysvar, never the phone's clock. The app shows the countdown in chain time too.
- **Session key.** At creation the owner names a key kept on the phone (Ed25519, its seed encrypted with an Android Keystore AES key) as `clock_in_authority`, and sends it 0.003 SOL for fees. That key can call `clock_in` and nothing else. It cannot move the stake, so daily clock-ins need no wallet screen. It also pays the fee for `settle`, which anyone may call and which pays only the owner.
- **Settle** refunds `total × kept / days`, burns the exact remainder (the vault always ends at zero), then closes the vault and the commitment. Both rents go back to the owner.
- **The program refuses** a clock-in below the target, a second clock-in for the same day, a clock-in outside the day's window, a clock-in signed by any other key, and settling before the end.

### Honest limits

- **The step count comes from the phone.** The program checks who clocks in, which day it is and that each day counts once. It cannot verify the steps themselves.
- The hardware counter counts since boot. The app stores a per-day baseline and carries steps over a reboot. Steps between the last time the app saw the counter and the start of a new day count toward the new day.
- Devnet only. Test SKR has no value and is not the real SKR token.
- **Gas sponsor.** The public devnet airdrop is often down or rate-limited. When it fails, the release APK falls back to a bundled devnet-only key that sends an empty wallet 0.015 SOL, enough for one pledge. That key holds only a little devnet SOL and has no authority over the program or the token. Builds without `sponsor.seed` in `local.properties` skip this step.

## Repository

| Path | What |
|---|---|
| `anchor-program/` | The program: `create_commitment`, `clock_in`, `settle`, `faucet` (Anchor 0.32) |
| `android-app/` | Kotlin + Jetpack Compose app: Mobile Wallet Adapter 2.2 and web3-solana |
| `cli/` | `e2e-devnet.mjs` runs a full pledge on the live program; `lib.mjs` holds the same instruction bytes the app builds |
| `docs/` | The site (GitHub Pages). It is also the wallet-adapter identity: `icon.png` |

### Build and test

```bash
# Program (Anchor 0.32.2, Solana CLI 2.x+)
cd anchor-program && anchor build --no-idl

# End-to-end on the live devnet program: faucet, create, session-key clock-in,
# refused double/stranger/early calls, settle by a third party (refund + burn)
npm install && node cli/e2e-devnet.mjs path/to/funded-keypair.json

# Android (JDK 17, Android SDK 37)
cd android-app && ./gradlew assembleDebug
# The app's own transaction code against the live program (needs a funded keys/deployer.json)
./gradlew testDebugUnitTest -Pe2e
```

The app talks to `https://api.devnet.solana.com` unless `android-app/local.properties` sets `rpc.url` to a keyed devnet RPC.

### Wallet notes

- The app connects with Mobile Wallet Adapter. The identity is `https://fannbe.github.io/PledgeX/` with a **relative** icon `icon.png`, because the adapter rejects an absolute icon URI.
- The wallet only **signs**. The app broadcasts through its own RPC and resends until the transaction confirms or its blockhash expires.
- Each wallet session starts with a fresh `authorize`, because Phantom refuses `reauthorize`.

## License

MIT
