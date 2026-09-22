package bio.cosy.flnet.cli.base;

import bio.cosy.flnet.cli.helper.CliException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.nio.file.Path;
import java.util.List;

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

    public abstract String getTypeLabel();

    public String getLabel() {
        return getTypeLabel() + " '" + name + "'";
    }

    public Path resolve(String relative) {
        if (directory == null) {
            throw new IllegalStateException(getLabel() + " has no directory yet");
        }
        return directory.resolve(relative);
    }

    public List<String> problems(Validator validator) {
        return validator.validate(this).stream().map(ConstraintViolation::getMessage).distinct().sorted().toList();
    }

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
