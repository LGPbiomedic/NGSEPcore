package ngsep.alignments;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.List;

import ngsep.genome.GenomicRegion;
import ngsep.main.CommandsDescriptor;
import ngsep.sequences.FMIndex;
import ngsep.sequences.RawRead;

public class ReadsAligner {

	public static void main(String[] args) throws Exception 
	{
		ReadsAligner instance = new ReadsAligner();
		int i = CommandsDescriptor.getInstance().loadOptions(instance, args);
		if(i<0) return;
		String fMIndexFile = args[i++];
		String readsFile = args[i++];
		
		instance.alignReads(fMIndexFile, readsFile, System.out);
			
	}

	private void alignReads( String fMIndexFile, String readsFile, PrintStream out)
			throws Exception, FileNotFoundException, IOException 
	{
		FMIndex fMIndex = loadIndex(fMIndexFile);
		
		FileInputStream fis = new FileInputStream(readsFile);
		BufferedReader in = new BufferedReader(new InputStreamReader(fis));
		RawRead read = RawRead.load(in);
		while(read!=null) 
		{
			List<GenomicRegion> r = fMIndex.search(read.getCharacters().toString());
			for (int k = 0; k < r.size(); k++) 
			{
				out.println(
						//1.query name
						read.getName()+"\t"+
								
						//2.Flag
						"0\t"+
						
						//3.reference sequence name
						r.get(k).getSequenceName()+"\t"+
						
						//4.POS
						r.get(k).getFirst()+"\t"+
						
						//5.MAPQ
						"255\t"+
						
						//6.CIGAR
						read.getLength()+"M\t"+
						
						//7. RNEXT
						"*\t"+
						
						//8. PNEXT
						"0\t"+
						
						//9. TLEN
						"0\t"+
						
						//10. SEQ
						read.getSequenceString()+"\t"+
						
						//11. QUAL
						read.getQualityScores()+"*"
						
						);
			}
			read = RawRead.load(in);
		}
		
		fis.close();
		out.println();
	}
	
//	private static void write(String path, String content) throws IOException 
//	{
//
//        File file = new File(path+".sam");
//
//        // if file doesnt exists, then create it
//        if (!file.exists()) {
//            file.createNewFile();
//        }
//
//        FileWriter fw = new FileWriter(file.getAbsoluteFile(),true);
//        BufferedWriter bw = new BufferedWriter(fw);
//        bw.write(content);
//        bw.close();
//	}

	public FMIndex loadIndex(String fMIndexFile) throws Exception 
	{
		FMIndex f = FMIndex.loadFromBinaries(fMIndexFile);
		return f;
	}

}