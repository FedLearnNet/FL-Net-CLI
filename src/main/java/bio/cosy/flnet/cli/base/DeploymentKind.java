package bio.cosy.flnet.cli.base;

/** The two docker compose deployments the CLI manages, each with any number of named instances. */
public enum DeploymentKind {
    PLATFORM("platform", "platforms", "FL-Net Platform"),
    CLIENT("client", "clients", "FL-Net Client");

    private final String bundle;
    private final String folder;
    private final String displayName;

    DeploymentKind(String bundle, String folder, String displayName) {
        this.bundle = bundle;
        this.folder = folder;
        this.displayName = displayName;
    }

    /** Name of the embedded bundle below {@code /bundles}, also the CLI command ({@code flnet client}). */
    public String bundle() {
        return bundle;
    }

    public String displayName() {
        return displayName;
    }

    /** Directory below the instances home: {@code clients} or {@code platforms}. */
    public String folder() {
        return folder;
    }
}
