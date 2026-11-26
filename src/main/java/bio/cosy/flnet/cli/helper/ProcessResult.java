package bio.cosy.flnet.cli.helper;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProcessResult {
    private int code;
    private String error;

    public boolean failed() {
        return code != 0;
    }

    // last line of the error output, usually the reason
    public String reason() {
        return error.lines().map(String::strip).filter(line -> !line.isEmpty()).reduce((first, second) -> second)
                .orElse("exit code " + code);
    }
}
