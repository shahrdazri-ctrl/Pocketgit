package com.pocketgit.diff;

import com.pocketgit.model.FileMode;
import java.util.List;

public final class DiffFormatter {
    private String mode(FileMode mode) { return mode==FileMode.EXECUTABLE_FILE?"100755":"100644"; }
    public String format(List<DiffResult> results) {
        var text=new StringBuilder();
        for(var result:results) {
            text.append("diff --pocketgit a/").append(result.path()).append(" b/").append(result.path()).append('\n');
            if(!result.oldExists())text.append("new file mode ").append(mode(result.newMode())).append('\n');
            else if(!result.newExists())text.append("deleted file mode ").append(mode(result.oldMode())).append('\n');
            else if(result.oldMode()!=result.newMode())text.append("old mode ").append(mode(result.oldMode())).append("\nnew mode ").append(mode(result.newMode())).append('\n');
            if(result.binary()) {text.append("Binary files differ: ").append(result.path()).append('\n');continue;}
            if(result.hunks().isEmpty())continue;
            text.append("--- ").append(result.oldExists()?"a/"+result.path():"/dev/null").append('\n');
            text.append("+++ ").append(result.newExists()?"b/"+result.path():"/dev/null").append('\n');
            for(var hunk:result.hunks()) {
                text.append("@@ -").append(hunk.oldStart()).append(',').append(hunk.oldCount()).append(" +").append(hunk.newStart()).append(',').append(hunk.newCount()).append(" @@\n");
                for(var line:hunk.lines()) {
                    text.append(switch(line.type()){case CONTEXT->' ';case ADDED->'+';case REMOVED->'-';}).append(line.text()).append('\n');
                    if(!line.terminated())text.append("\\ No newline at end of file\n");
                }
            }
        }
        return text.toString();
    }
}
