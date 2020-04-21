package ngsep.genome;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import ngsep.main.CommandsDescriptor;
import ngsep.main.OptionValuesDecoder;
import ngsep.main.ProgressNotifier;
import ngsep.sequences.QualifiedSequence;
import ngsep.sequences.QualifiedSequenceList;
import ngsep.sequences.io.FastaFileReader;
import ngsep.sequences.io.FastaSequencesHandler;
import ngsep.transcriptome.ProteinTranslator;
import ngsep.transcriptome.Transcript;

public class CDNACatalogAligner {
	// Constants for default values
	public static final String DEF_OUT_PREFIX = "organismsAlignment";
	public static final byte DEF_KMER_LENGTH = HomologRelationshipsFinder.DEF_KMER_LENGTH;
	public static final int DEF_MIN_PCT_KMERS = HomologRelationshipsFinder.DEF_MIN_PCT_KMERS;
	public static final int DEF_MAX_HOMOLOGS_UNIT = 3;
	
	// Logging and progress
	private Logger log = Logger.getLogger(CDNACatalogAligner.class.getName());
	private ProgressNotifier progressNotifier=null;
	
	// Parameters
	private List<HomologyCatalog> cdnaCatalogs = new ArrayList<>();
	private ProteinTranslator translator = new ProteinTranslator();
	private String outputPrefix = DEF_OUT_PREFIX;
	private int maxHomologsUnit = DEF_MAX_HOMOLOGS_UNIT;
	private boolean skipMCL= false;
	
	// Model attributes
	private HomologRelationshipsFinder homologRelationshipsFinder = new HomologRelationshipsFinder();
	private List<HomologyEdge> homologyEdges = new ArrayList<HomologyEdge>();
	private List<List<HomologyUnit>> orthologyUnitClusters=new ArrayList<>();
	
	//logging
	public Logger getLog() {
		return log;
	}
	public void setLog(Logger log) {
		this.log = log;
	}

	public ProgressNotifier getProgressNotifier() {
		return progressNotifier;
	}
	public void setProgressNotifier(ProgressNotifier progressNotifier) {
		this.progressNotifier = progressNotifier;
	}
	
	//program arguments
	public String getOutputPrefix() {
		return outputPrefix;
	}
	public void setOutputPrefix(String outputPrefix) {
		this.outputPrefix = outputPrefix;
	}
	//program arguments
	public boolean getSkipMCL() {
		return skipMCL;
	}
	public void setSkipMCL(boolean skipMCL) {
		this.skipMCL = skipMCL;
	}
	public byte getKmerLength() {
		return homologRelationshipsFinder.getKmerLength();
	}
	public void setKmerLength(byte kmerLength) {
		homologRelationshipsFinder.setKmerLength(kmerLength);
	}
	public void setKmerLength(String value) {
		setKmerLength((byte)OptionValuesDecoder.decode(value, Byte.class));
	}
	public int getMinPctKmers() {
		return homologRelationshipsFinder.getMinPctKmers();
	}
	public void setMinPctKmers(int minPctKmers) {
		homologRelationshipsFinder.setMinPctKmers(minPctKmers);
	}
	public void setMinPctKmers(String value) {
		setMinPctKmers((int)OptionValuesDecoder.decode(value, Integer.class));
	}
	
	public static void main(String[] args) throws Exception {
		CDNACatalogAligner instance = new CDNACatalogAligner();
		int i = CommandsDescriptor.getInstance().loadOptions(instance, args);
		while(i<args.length-1) {
			String fileOrganism = args[i++];
			instance.loadFile(fileOrganism);
		}
		instance.run();
	}
	
	private void loadFile(String fileName) throws IOException {
		FastaSequencesHandler handler = new FastaSequencesHandler();
		List<QualifiedSequence> sequences = handler.loadSequences(fileName);
		
		List<HomologyUnit> units = new ArrayList<>();
		for(QualifiedSequence seq : sequences) {
			HomologyUnit unit = new HomologyUnit(cdnaCatalogs.size()+1, seq.getName(), translator.getProteinSequence(seq.getCharacters()));
			units.add(unit);
		}
		
		HomologyCatalog catalog = new HomologyCatalog(units);
		cdnaCatalogs.add(catalog);
	}
	
	public void run () throws IOException {
		logParameters();
		if(cdnaCatalogs.size()==0) throw new IOException("At least one organism's data should be provided");
		if(outputPrefix==null) throw new IOException("A prefix for output files is required");
		alignCatalogs();
		printResults();
		log.info("Process finished");
	}
	
	public void logParameters() {
		ByteArrayOutputStream os = new ByteArrayOutputStream();
		PrintStream out = new PrintStream(os);
		out.println("Loaded: "+ cdnaCatalogs.size()+" annotated genomes");
		out.println("Output prefix:"+ outputPrefix);
		out.println("K-mer length: "+ getKmerLength());
		out.println("Minimum percentage of k-mers to call orthologs: "+ getMinPctKmers());
		log.info(os.toString());
	}
	
	public void alignCatalogs() {
		for(int i=0;i<cdnaCatalogs.size();i++) {
			HomologyCatalog catalog = cdnaCatalogs.get(i);
			homologyEdges.addAll(homologRelationshipsFinder.calculateParalogsOrganism(catalog));
			log.info("Paralogs found for Organism: "+ homologyEdges.size());
		}
		
		
		for(int i=0;i<cdnaCatalogs.size();i++) {
			HomologyCatalog catalog1 = cdnaCatalogs.get(i);
			for (int j=0;j<cdnaCatalogs.size();j++) {
				HomologyCatalog catalog2 = cdnaCatalogs.get(i);
				if(i!=j) homologyEdges.addAll(homologRelationshipsFinder.calculateOrthologs(catalog1, catalog2));
			}
		}
		HomologClustersCalculator calculator = new HomologClustersCalculator();
		calculator.setLog(log);
		orthologyUnitClusters = calculator.clusterHomologsOrganisms(cdnaCatalogs, homologyEdges, skipMCL);
	}
	
	public void printResults() throws FileNotFoundException {
		//Print ortholog clusters
		try (PrintStream outClusters = new PrintStream(outputPrefix+"_clusters.txt");) {
			for(List<HomologyUnit> cluster:orthologyUnitClusters) {
				outClusters.print(cluster.get(0).getId());
				for(int i=1;i<cluster.size();i++) {
					HomologyUnit unit = cluster.get(i);
					outClusters.print("\t"+unit.getId());
				}
				outClusters.println();
			}
		}
	}
}
