package pl.marcinchwedczuk.template.domain.protocol;

import java.util.List;

/**
 * Everything received in response to one command.
 * {@code lines} contains all text lines except the final one (INFO, TEST, DATA, END, ...).
 */
public record CommandResult(String command, ResponseLine finalLine,
                            List<ResponseLine> lines, List<DataBlock> blocks) {
    public boolean isOk() {
        return finalLine.type() == ResponseLine.Type.OK;
    }

    public List<ResponseLine> linesOfType(ResponseLine.Type type) {
        return lines.stream().filter(l -> l.type() == type).toList();
    }
}
