// Parro Health — connexion par clé d'accès (WebAuthn) : passkey ou clé de sécurité FIDO2.
"use strict";
const b64u = {
  enc: (buf) => btoa(String.fromCharCode(...new Uint8Array(buf))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, ""),
  dec: (s) => Uint8Array.from(atob(s.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((s.length + 3) % 4)), (c) => c.charCodeAt(0)),
};
const btn = document.getElementById("pk");
const err = document.getElementById("pkerr");
if (btn) btn.addEventListener("click", async () => {
  const form = document.getElementById("f");
  err.textContent = "";
  if (!form.password.value) { err.textContent = "Saisis d'abord ton mot de passe."; form.password.focus(); return; }
  if (!window.PublicKeyCredential) { err.textContent = "Ce navigateur ne gère pas les clés d'accès : utilise le code."; return; }
  const scope = btn.dataset.req ? { req: btn.dataset.req } : { gate: btn.dataset.gate };
  try {
    const r = await fetch("/login/cle-options", { method: "POST", body: new URLSearchParams(scope) });
    const o = await r.json();
    if (!r.ok) throw new Error(o.error || "options refusées");
    const cred = await navigator.credentials.get({ publicKey: {
      challenge: b64u.dec(o.challenge), rpId: o.rpId, timeout: o.timeout, userVerification: o.userVerification,
      allowCredentials: (o.allowCredentials || []).map((c) => ({ type: "public-key", id: b64u.dec(c.id), transports: c.transports })),
    } });
    const a = cred.response;
    form.webauthn.value = JSON.stringify({
      id: cred.id, rawId: b64u.enc(cred.rawId), type: cred.type,
      response: { clientDataJSON: b64u.enc(a.clientDataJSON), authenticatorData: b64u.enc(a.authenticatorData),
                  signature: b64u.enc(a.signature), userHandle: a.userHandle ? b64u.enc(a.userHandle) : null },
      clientExtensionResults: {},
    });
    if (form.code) form.code.removeAttribute("required");
    form.submit();
  } catch (e) {
    err.textContent = "Clé non utilisée : " + (e.message || e) + ". Réessaie ou utilise le code.";
  }
});
