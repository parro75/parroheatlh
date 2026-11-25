# Parro Health — règles R8 (prudentes : l'appli est petite, le poids vient des bibliothèques).
# Code de l'appli intact (workers instanciés par nom, activités, récepteur de widget, réflexion éventuelle).
-keep class fr.parro.santesync.** { *; }
# Types d'enregistrements Health Connect gardés tels quels (lus via KClass, permissions dérivées de la classe).
-keep class androidx.health.connect.client.records.** { *; }
# Lignes et fichiers lisibles dans les traces de plantage.
-keepattributes SourceFile,LineNumberTable
# protobuf-lite de Health Connect (lu par réflexion) : classes ET champs gardés, en plus de la règle de la
# bibliothèque qui ne garde que les champs. Coût : quelques centaines de Ko ; risque évité : lecture HC cassée.
-keep class androidx.health.platform.client.** { *; }
