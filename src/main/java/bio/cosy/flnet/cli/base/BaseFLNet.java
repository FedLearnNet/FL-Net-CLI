package bio.cosy.flnet.cli.base;

import bio.cosy.flnet.cli.support.CliException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Base of everything the CLI sets up: something with a name that lives in a directory. Subclasses
 * hold all their settings; their rules are Bean Validation constraints, checked with {@link #requireValid}.
 */
@Getter
@Setter
public abstract class BaseFLNet {

    @NotBlank(message = "The name is required.")
    private String name;
    @NotNull(message = "No directory is set.")
    private Path directory;

    public void setDirectory(Path directory) {
        this.directory = directory == null ? null : directory.toAbsolutePath().normalize();
    }

    /** Human readable kind, e.g. "client", used in messages. */
    public abstract String getTypeLabel();

    /** "client 'site-b'" */
    public String getLabel() {
        return getTypeLabel() + " '" + name + "'";
    }

    /** A path inside the directory. */
    public Path resolve(String relative) {
        if (directory == null) {
            throw new IllegalStateException(getLabel() + " has no directory yet");
        }
        return directory.resolve(relative);
    }

    public boolean directoryExists() {
        return directory != null && Files.isDirectory(directory);
    }

    /** All problems of the current settings, sorted; empty when valid. */
    public List<String> problems(Validator validator) {
        return validator.validate(this).stream().map(ConstraintViolation::getMessage).distinct().sorted().toList();
    }

    /** Fails with every problem at once, so users can fix them in one go. */
    public void requireValid(Validator validator) {
        List<String> problems = problems(validator);
        if (!problems.isEmpty()) {
            throw CliException.usage("Invalid " + getTypeLabel() + " configuration: " + String.join(" ", problems));
        }
    }

    @Override
    public String toString() {
        return getLabel() + (directory == null ? "" : " in " + directory);
    }
}
