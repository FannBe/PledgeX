// Points the test SKR token's Metaplex metadata at docs/token/metadata.json and, when
// asked, hands the update authority to another key.
// Usage: node cli/token-metadata.mjs <authority.json> [payer.json] [newAuthorityPubkey]
import { readFileSync } from "node:fs";
import { createUmi } from "@metaplex-foundation/umi-bundle-defaults";
import { mplTokenMetadata, fetchMetadataFromSeeds, updateV1 } from "@metaplex-foundation/mpl-token-metadata";
import { createSignerFromKeypair, signerIdentity, publicKey, some, none } from "@metaplex-foundation/umi";
import { rpcUrl, SKR_MINT } from "./lib.mjs";

const NAME = "PledgeX Test SKR";
const URI = "https://fannbe.github.io/PledgeX/token/metadata.json";

const [authorityPath, payerPath = authorityPath, newAuthority] = process.argv.slice(2);
if (!authorityPath) { console.error("usage: node cli/token-metadata.mjs <authority.json> [payer.json] [newAuthorityPubkey]"); process.exit(1); }

const umi = createUmi(rpcUrl()).use(mplTokenMetadata());
const load = (p) => createSignerFromKeypair(umi, umi.eddsa.createKeypairFromSecretKey(Uint8Array.from(JSON.parse(readFileSync(p, "utf8")))));
const authority = load(authorityPath);
umi.use(signerIdentity(load(payerPath)));

const mint = publicKey(SKR_MINT.toBase58());
const current = await fetchMetadataFromSeeds(umi, { mint });
console.log("before:", current.name, current.uri, "update authority", current.updateAuthority);

const { signature } = await updateV1(umi, {
  mint,
  authority,
  data: some({ ...current, name: NAME, uri: URI }),
  newUpdateAuthority: newAuthority ? some(publicKey(newAuthority)) : none(),
}).sendAndConfirm(umi);

const after = await fetchMetadataFromSeeds(umi, { mint });
console.log("after: ", after.name, after.uri, "update authority", after.updateAuthority);
console.log("signature bytes", Buffer.from(signature).toString("hex").slice(0, 16), "…");
