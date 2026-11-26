package fr.parro.santesync

import android.os.SystemClock

/**
 * Verrouillage sans délai de grâce (26/11/2026) : l'app se verrouille à CHAQUE passage en arrière-plan.
 * Exception unique : un aller-retour que l'utilisateur vient de déclencher lui-même depuis l'app (connexion
 * dans le navigateur, choix d'un fichier, autorisations Health Connect, ouverture ou partage de la fiche
 * PDF…). L'exemption ne vaut que pour UN retour, dans les 5 minutes.
 */
object LockGuard {
    private const val WINDOW_MS = 5 * 60_000L
    @Volatile private var allowUntil = 0L

    /** À appeler juste avant d'ouvrir une autre appli à la demande de l'utilisateur. */
    fun leaving() {
        allowUntil = SystemClock.elapsedRealtime() + WINDOW_MS
    }

    /** Vrai (une seule fois) si le retour au premier plan suit un aller-retour volontaire. */
    fun consume(): Boolean {
        val ok = SystemClock.elapsedRealtime() < allowUntil
        allowUntil = 0L
        return ok
    }
}
