plugins {
    id("geyser.shadow-conventions")
    id("net.kyori.indra.publishing")
}

indra {
    configurePublications {
        if (shouldAddBranchName()) {
            version = versionWithBranchName()
        }
    }
}
