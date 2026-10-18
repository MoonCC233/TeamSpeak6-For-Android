import type { KeyObject } from "node:crypto";
import type { Crypt } from "../crypto/crypt.js";
import { importPublicKey } from "../crypto/identity.js";
import { verifySign, getSharedSecret2 } from "../crypto/primitives.js";
import { parseLicenses } from "./license.js";
import { HandshakeError } from "../errors.js";

/**
 * CryptoInit2 performs the second stage of crypto initialization (Ed25519 ECDH).
 * Mirrors Go's handshake.CryptoInit2.
 */
export function cryptoInit2(
  crypt: Crypt,
  license: string,
  omega: string,
  proof: string,
  beta: string,
  privateKey: Uint8Array,
): void {
  if (crypt.alpha.length === 0) {
    throw new HandshakeError("initivexpand2 arrived before clientinitiv was sent");
  }

  const licenseBytes = Buffer.from(license, "base64");
  const omegaBytes = Buffer.from(omega, "base64");
  const proofBytes = Buffer.from(proof, "base64");
  const betaBytes = Buffer.from(beta, "base64");

  let serverPubKey: KeyObject;
  try {
    serverPubKey = importPublicKey(omegaBytes);
  } catch (error) {
    throw new HandshakeError("the server's public key is invalid", { cause: error });
  }
  if (!verifySign(serverPubKey, licenseBytes, proofBytes)) {
    throw new HandshakeError("the server's license proof is not valid");
  }

  let sharedSecret: Uint8Array;
  try {
    const key = parseLicenses(licenseBytes).deriveKey();
    sharedSecret = getSharedSecret2(key, privateKey);
  } catch (error) {
    throw new HandshakeError("the server's license is invalid", { cause: error });
  }

  crypt.setSharedSecret(crypt.alpha, betaBytes, sharedSecret);
}
