package is.landsbokasafn.deduplicator.heritrix;

import static is.landsbokasafn.deduplicator.IndexFields.DATE;
import static is.landsbokasafn.deduplicator.IndexFields.DIGEST;
import static is.landsbokasafn.deduplicator.IndexFields.ORIGINAL_RECORD_ID;
import static is.landsbokasafn.deduplicator.IndexFields.URL;
import static is.landsbokasafn.deduplicator.IndexFields.URL_CANONICALIZED;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.AtomicReader;
import org.apache.lucene.index.AtomicReaderContext;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.NIOFSDirectory;
import org.apache.lucene.util.Bits;
import org.archive.modules.revisit.IdenticalPayloadDigestRevisit;
import org.archive.util.ArchiveUtils;
import org.archive.util.BloomFilter64bit;
import org.springframework.beans.factory.InitializingBean;

public class LuceneIndexSearcher implements Index, InitializingBean {
    private static Logger logger = Logger.getLogger(LuceneIndexSearcher.class.getName());

    protected IndexSearcher searcher = null;
    protected DirectoryReader dReader = null;
    
    protected boolean urlIndexed = false;  // Is the URL field indexed
    protected boolean digestIndexed = false; // Is the Digest field indexed
    protected boolean canoncialAvailable = false; // Is the URL_Canonicalized field present. Indexed if URL is.

    private String indexLocation;
    private int numDocs = -1;
    /**
     * Set the location of the index in the filesystem. Changing this value after the bean has been 
     * initialized will have no effect.
     * @param indexLocation The location of the index.
     */
    public void setIndexLocation(String indexLocation) {
        this.indexLocation=indexLocation;
    }
    public String getIndexLocation() {
        return indexLocation;
    }

    protected SearchStrategy strategy;
    /**
     * Set the search strategy to employ.
     *  
     * @param strategy The search strategy to employ
     * @see SearchStrategy
     */
	public void setSearchStrategy(SearchStrategy strategy) {
		if (searcher!=null) {
			verifyStrategy(strategy);
		}
		this.strategy = strategy;
	}
	public SearchStrategy getSearchStrategy() {
		return strategy;
	}
	
	private BloomFilter64bit bf = null;
	private AtomicInteger bloomHits = new AtomicInteger(); 
	protected boolean useBloomFilter = false;
	public boolean getUseBloomFilter() {
		return useBloomFilter;
	}
	/**
	 * If true, a bloom filter will be constructed from all available digests in the index.
	 * This happens on crawl build. It is advised (but not required) to wait until the bloom filter is fully populated
	 * before moving to crawl launch.  
	 * Modifying this setting at runtime will have no effect.
	 * @param useBloomFilter
	 */
	public void setUseBloomFilter(boolean useBloomFilter) {
		this.useBloomFilter = useBloomFilter;
	}
	
	@Override
	public void afterPropertiesSet() throws Exception {
    	openIndex(indexLocation);
    	verifyStrategy(strategy);
    	if (getUseBloomFilter()){
    		setupBloomFilter();
    	}
    }
    
    private void openIndex(String indexLocation) {
    	if (searcher!=null) {
    		throw new IllegalStateException("Already have an open index");
    	}
    	try {
            dReader = DirectoryReader.open(new NIOFSDirectory(new File(indexLocation)));
            searcher = new IndexSearcher(dReader);
        } catch (Exception e) {
        	throw new IllegalArgumentException("Unable to find/open index at " + indexLocation,e);
        } 
    	inspectIndex();
    }

    private void inspectIndex() {
    	// Determine index makeup
        urlIndexed = isFieldIndexed(URL.name());
        digestIndexed = isFieldIndexed(DIGEST.name());
        if (digestIndexed==false) {
        	throw new IllegalStateException("DIGEST fields must be indexed.");
        }
        try {
            boolean canonicalIndexed = isFieldIndexed(URL_CANONICALIZED.name());
            if (canonicalIndexed==urlIndexed) {
            	canoncialAvailable=true;
            } else {
            	logger.severe("URL_CANONICALIZED and URL fields disagree on indexing. "
            			+ "Either both must be indexed or neither. Proceeding as if URL_CANONICALIZED "
            			+ "was not available.");
            }
        } catch (NullPointerException e) {
        	canoncialAvailable=false;
        }
        numDocs = dReader.numDocs();
    }
    
    /**
     * Verify that the current index supports the selected strategy. I.e. that the necessary fields are indexed.
     * @param strategy The strategy to verify
     * @throws IllegalStateException if the strategy can not be carried out for the current index
     */
    private void verifyStrategy(SearchStrategy strategy) {
    	if (strategy==SearchStrategy.URL_EXACT || strategy==SearchStrategy.URL_CANONICAL) {
    		if (!urlIndexed) {
    			throw new IllegalStateException("URL must be indexed for search strategy " + strategy.name());
    		}
    	}
    	if (strategy==SearchStrategy.URL_CANONICAL) {
    		if (!canoncialAvailable) {
    			throw new IllegalStateException("Canonical URL must be available for search strategy " + 
    					strategy.name());
    		}
    	}
    }
    
	private void setupBloomFilter() {
        bf = new BloomFilter64bit(dReader.maxDoc(),22);
        BuildBloom buildBloom = new BuildBloom(); 
        Thread myThread = new Thread(buildBloom);
        myThread.setDaemon(true); 
        myThread.start(); 
	}
	class BuildBloom implements Runnable{
		@Override
		public void run() {
			try {
		        Bits liveDocs = MultiFields.getLiveDocs(dReader);
		        long start = System.nanoTime();
		        int i=0;
		        for ( ; i<dReader.maxDoc() ; i++) {
		            if (liveDocs != null && !liveDocs.get(i))
		                continue;
	
		            Document doc = dReader.document(i);
		            bf.add(doc.get(DIGEST.name()));
		        }
		        logger.info("BloomFilter ready. Read " + i + " documents in " + 
		        		ArchiveUtils.formatMillisecondsToConventional((System.nanoTime()-start)/1000000));
			} catch (IOException e) {
				throw new IllegalStateException("Error building bloom filter for index " + indexLocation, e);
			}
		}
	}
	
    protected boolean isFieldIndexed(String field) {
        IndexReader reader = searcher.getIndexReader();
        for (AtomicReaderContext rc : reader.leaves()) {
	        AtomicReader ar = rc.reader();
	        FieldInfos fis = ar.getFieldInfos();
	        if (!fis.fieldInfo(field).isIndexed()) {
	        	// All leaves must agree for us to return true
	        	return false;
	        }
        }
    	return true;
    }
    
    @Override
	public IdenticalPayloadDigestRevisit lookup(String url, String canonicalizedUrl, String digest) {
    	if (bf!=null && !bf.contains(digest)) {
    		bloomHits.incrementAndGet();
    		return null;
    	}
    	switch (strategy) {
		case URL_EXACT:
			return lookupUrlExact(url, digest);
		case URL_CANONICAL:
			return lookupUrlCanonical(canonicalizedUrl, digest);
		case DIGEST_ANY:
			return lookupDigestAny(url, digest);
		case DIGEST_URL_PREFERRED:
			return lookupDigestUrlPrefered(url,canonicalizedUrl,digest);
    	}
    	return null;
    }
    
    protected IdenticalPayloadDigestRevisit lookupUrlExact(final String url, final String digest) {
    	BooleanQuery q = new BooleanQuery();
    	q.add(new TermQuery(new Term(URL.name(), url)), Occur.MUST);
    	q.add(new TermQuery(new Term(DIGEST.name(), digest)), Occur.MUST);
    	return query(q);
    }
    
    protected IdenticalPayloadDigestRevisit lookupUrlCanonical(final String canonicalizedUrl, final String digest) {
    	BooleanQuery q = new BooleanQuery();
    	q.add(new TermQuery(new Term(URL_CANONICALIZED.name(), canonicalizedUrl)), Occur.MUST);
    	q.add(new TermQuery(new Term(DIGEST.name(), digest)), Occur.MUST);
    	return query(q);
    }
    
    protected IdenticalPayloadDigestRevisit lookupDigestUrlPrefered(
    		final String url, final String canonicalizedUrl, final String digest) {
    	BooleanQuery q = new BooleanQuery();
    	q.add(new TermQuery(new Term(DIGEST.name(), digest)), Occur.MUST);
    	if (urlIndexed) {
	    	if (canoncialAvailable) {
	    		q.add(new TermQuery(new Term(URL_CANONICALIZED.name(), canonicalizedUrl)), Occur.SHOULD);
	    	}
    		q.add(new TermQuery(new Term(URL.name(), url)), Occur.SHOULD);
    	}
        return query(q);
    }

    protected IdenticalPayloadDigestRevisit lookupDigestAny(final String url, final String digest) {
        return query(new TermQuery(new Term(DIGEST.name(), digest)));
    }

    /**
     * Do a search for duplicates in the index based on the provided query. 
     * @param query The query to perform. Query must be structured so that any results returned are valid duplicates
     *              (i.e. mandatory search term on appropriate digest) and structured so that the first hit is 
     *              the most appropriate one to use if there are multiple hits.
     * @return A duplicate based on the first hit of the query or null if query returned no hits.
     */
	protected IdenticalPayloadDigestRevisit query(Query query) {
		IdenticalPayloadDigestRevisit duplicate = null; 
		try {
			ScoreDoc[] hits = searcher.search(query, null, 1).scoreDocs;
            if(hits != null && hits.length > 0){
                duplicate = wrap(searcher.doc(hits[0].doc));
            }
		} catch (IOException e) {
			logger.log(Level.SEVERE, "Error accessing index.", e);
		}
		return duplicate; 
	}
	
	protected IdenticalPayloadDigestRevisit wrap(Document doc) {
		IdenticalPayloadDigestRevisit duplicate = new IdenticalPayloadDigestRevisit(doc.get(DIGEST.name()));
    	
    	duplicate.setRefersToTargetURI(doc.get(URL.name()));
   		duplicate.setRefersToDate(doc.get(DATE.name()));
    	
    	String refersToRecordID = doc.get(ORIGINAL_RECORD_ID.name());
    	if (refersToRecordID!=null && !refersToRecordID.isEmpty()) {
    		duplicate.setRefersToRecordID(refersToRecordID);
    	}

    	return duplicate;
	}

    public String getInfo() {
    	StringBuilder sb = new StringBuilder();
    	sb.append(LuceneIndexSearcher.class.getCanonicalName());
    	sb.append("\n");
    	sb.append(" URL indexed: " + urlIndexed);
    	sb.append("\n");
    	sb.append(" Digest indexed: " + digestIndexed);
    	sb.append("\n");
    	sb.append(" Canonical URL available: " + canoncialAvailable);
    	sb.append("\n");
    	sb.append(" Search strategy: " + getSearchStrategy());
    	sb.append("\n");
		sb.append(" Records in index: ");
		sb.append(numDocs);
    	sb.append("\n");
    	if (bf!=null) {
    		sb.append(" BloomFilter size: ");
    		sb.append(bf.size());
        	sb.append("\n");
    		sb.append(" BloomFilter hits: ");
    		sb.append(bloomHits.get());
        	sb.append("\n");
    	}
    	
    	return sb.toString();
    }

	public void close() {
		try {
			if (dReader != null) {
				dReader.close();
			}
		} catch (IOException e) {
			logger.log(Level.SEVERE,"Error closing index",e);
		}
	}
}
