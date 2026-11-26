// Parro Health — enregistrement d'une clé d'accès (WebAuthn).
"use strict";
const b64u = {
  enc: (buf) => btoa(String.fromCharCode(...new Uint8Array(buf))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, ""),
  dec: (s) => Uint8Array.from(atob(s.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((s.length + 3) % 4)), (c) => c.charCodeAt(0)),
};
const add = document.getElementById("ajouter");
const err = document.getElementById("pkerr");
if (add) add.addEventListener("click", async () => {
  err.textContent = "";
  const name = document.getElementById("nom").value.trim() || "clé";
  try {
    const r = await fetch("/passkeys/options", { method: "POST" });
    const o = await r.json();
    if (!r.ok) throw new Error(o.error || "options refusées");
    const cred = await navigator.credentials.create({ publicKey: {
      challenge: b64u.dec(o.challenge), rp: o.rp, timeout: o.timeout, attestation: o.attestation,
      user: { id: b64u.dec(o.user.id), name: o.user.name, displayName: o.user.displayName },
      pubKeyCredParams: o.pubKeyCredParams, authenticatorSelection: o.authenticatorSelection,
      excludeCredentials: (o.excludeCredentials || []).map((c) => ({ type: "public-key", id: b64u.dec(c.id), transports: c.transports })),
    } });
    const a = cred.response;
    const body = { name, credential: {
      id: cred.id, rawId: b64u.enc(cred.rawId), type: cred.type,
      response: { clientDataJSON: b64u.enc(a.clientDataJSON), attestationObject: b64u.enc(a.attestationObject),
                  transports: a.getTransports ? a.getTransports() : [] },
      clientExtensionResults: {},
    } };
    const s = await fetch("/passkeys/enregistrer", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
    const res = await s.json();
    if (!s.ok) throw new Error(res.error || "enregistrement refusé");
    location.reload();
  } catch (e) {
    err.textContent = "Clé non enregistrée : " + (e.message || e);
  }
});
