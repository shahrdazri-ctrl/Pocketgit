package com.pocketgit.diff;

import com.pocketgit.model.FileMode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static com.pocketgit.diff.DiffLine.Type.*;

/** LCS edit script with shared prefix/suffix trimming and three context lines. */
public final class DiffEngine {
    public static final long MAX_CELLS = 4_000_000;
    private record Line(String text, boolean terminated) {}
    private String text(byte[] bytes) {
        for (byte b : bytes) if (b == 0) return null;
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (java.nio.charset.CharacterCodingException binary) { return null; }
    }
    private List<Line> lines(String text) {
        var result = new ArrayList<Line>(); int start=0;
        for (int i=0;i<text.length();i++) if (text.charAt(i)=='\n') { result.add(new Line(text.substring(start,i),true)); start=i+1; }
        if (start<text.length()) result.add(new Line(text.substring(start),false)); return result;
    }
    public DiffResult diff(String path, byte[] before, byte[] after, FileMode oldMode, FileMode newMode) throws IOException {
        boolean oldExists=before!=null, newExists=after!=null;
        byte[] oldBytes=oldExists?before:new byte[0], newBytes=newExists?after:new byte[0];
        if (Arrays.equals(oldBytes,newBytes)) return new DiffResult(path,false,oldExists,newExists,oldMode,newMode,List.of());
        String oldText=text(oldBytes), newText=text(newBytes);
        if (oldText==null || newText==null) return new DiffResult(path,true,oldExists,newExists,oldMode,newMode,List.of());
        var a=lines(oldText); var b=lines(newText); int prefix=0,suffix=0;
        while(prefix<a.size() && prefix<b.size() && a.get(prefix).equals(b.get(prefix)))prefix++;
        while(suffix<a.size()-prefix && suffix<b.size()-prefix && a.get(a.size()-1-suffix).equals(b.get(b.size()-1-suffix)))suffix++;
        int n=a.size()-prefix-suffix,m=b.size()-prefix-suffix;
        if((long)(n+1)*(m+1)>MAX_CELLS)throw new IOException("text diff exceeds 4,000,000 LCS cells: "+path);
        int[][] lcs=new int[n+1][m+1];
        for(int i=n-1;i>=0;i--)for(int j=m-1;j>=0;j--)lcs[i][j]=a.get(prefix+i).equals(b.get(prefix+j))?1+lcs[i+1][j+1]:Math.max(lcs[i+1][j],lcs[i][j+1]);
        var edits=new ArrayList<DiffLine>();
        for(int i=0;i<prefix;i++)edits.add(line(CONTEXT,a.get(i)));
        int i=0,j=0;
        while(i<n || j<m) {
            if(i<n && j<m && a.get(prefix+i).equals(b.get(prefix+j))) {edits.add(line(CONTEXT,a.get(prefix+i)));i++;j++;}
            else if(i<n && (j==m || lcs[i+1][j]>=lcs[i][j+1]))edits.add(line(REMOVED,a.get(prefix+i++)));
            else edits.add(line(ADDED,b.get(prefix+j++)));
        }
        for(i=a.size()-suffix;i<a.size();i++)edits.add(line(CONTEXT,a.get(i)));
        return new DiffResult(path,false,oldExists,newExists,oldMode,newMode,hunks(edits));
    }
    private DiffLine line(DiffLine.Type type,Line line) { return new DiffLine(type,line.text(),line.terminated()); }
    private List<DiffHunk> hunks(List<DiffLine> edits) {
        int[] old=new int[edits.size()+1], next=new int[edits.size()+1];
        for(int i=0;i<edits.size();i++) {old[i+1]=old[i]+(edits.get(i).type()==ADDED?0:1);next[i+1]=next[i]+(edits.get(i).type()==REMOVED?0:1);}
        var result=new ArrayList<DiffHunk>(); int cursor=0;
        while(cursor<edits.size()) {
            while(cursor<edits.size() && edits.get(cursor).type()==CONTEXT)cursor++;
            if(cursor==edits.size())break;
            int start=Math.max(0,cursor-3),last=cursor;
            while(true) {
                int candidate=last+1; while(candidate<edits.size() && edits.get(candidate).type()==CONTEXT)candidate++;
                if(candidate==edits.size() || candidate-last>7)break; last=candidate;
            }
            int end=Math.min(edits.size(),last+4),oldCount=old[end]-old[start],newCount=next[end]-next[start];
            result.add(new DiffHunk(old[start]+(oldCount==0?0:1),oldCount,next[start]+(newCount==0?0:1),newCount,edits.subList(start,end)));
            cursor=end;
        }
        return result;
    }
}
