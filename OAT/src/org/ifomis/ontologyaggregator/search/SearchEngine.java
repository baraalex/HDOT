package org.ifomis.ontologyaggregator.search;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Stack;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
import org.ifomis.ontologyaggregator.notifications.EmailSender;
import org.ifomis.ontologyaggregator.util.Configuration;
import org.semanticweb.owlapi.model.IRI;

import uk.ac.ebi.ontocat.OntologyService;
import uk.ac.ebi.ontocat.OntologyTerm;
import uk.ac.ebi.ontocat.bioportal.BioportalOntologyService;
import uk.ac.ebi.ontocat.virtual.CompositeServiceNoThreads;
import uk.ac.ebi.ontocat.virtual.SortedSubsetDecorator;

/**
 * The search engine searches a term via the REST interface in BioPortal with
 * respect to a predefined list of ontologies.
 * 
 * @author Nikolina
 * 
 */
public class SearchEngine {

	/**
	 * list of preferred ontologies
	 */
	private List<String> ontologiesList;

	private static final Logger log = Logger.getLogger(SearchEngine.class);

	/**
	 * The date format for the time stamp.
	 */
	private DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm");

	/**
	 * The time as a unique stamp in order to distinguish the produced files.
	 */
	private String date = dateFormat.format(new Date());

	/**
	 * Stores the stacks that keep the paths to root for the searched terms.
	 */
	private List<List<Stack<OntologyTerm>>> listOfPaths;

	/**
	 * the current term for which we run the search
	 */
	private String searchedTerm;

	private OntologyService restrictedBps;
	private EmailSender mailSender;

	public SearchEngine(IRI fileWithOntologies) throws IOException {

		File fileOntologies = new File(fileWithOntologies.toURI());

		this.ontologiesList = FileUtils.readLines(fileOntologies);
		this.mailSender = new EmailSender();

		log.debug("List of ontologies imported from file: "
				+ fileWithOntologies);

	}

	/**
	 * Searches the given term in BioPortal. The the ontologyList specifies the
	 * sorting of the ontologies.
	 * 
	 * @return list of stacks that store the root paths of the first 5 hits
	 * @throws Exception
	 */
	public List<List<Stack<OntologyTerm>>> searchTermInBioPortal(String term)
			throws Exception {

		OntologyService bps = CompositeServiceNoThreads
				.getService(new BioportalOntologyService());

		// create a restricted BioPortal ontology service that searches in the
		// ontologies specified in the given list and considers their order
		restrictedBps = SortedSubsetDecorator.getService(bps,
				this.ontologiesList);

		log.debug("Ontology service according to predefined list of ontologies is created");

		this.searchedTerm = term;

		log.info("Search for term: " + term);

		List<OntologyTerm> listWithHits = restrictedBps.searchAll(term);

		RootpathExtractor pathExtractor = new RootpathExtractor(searchedTerm,
				date);
		if (listWithHits == null) {
			log.info("No results for " + term);
			// continue;
			mailSender.sendMail("NO RESULTS RETRIEVED FROM BioPortal",
					"BioPortal has retrived no results for the term\" "
							+ searchedTerm
							+ "\"\n or the server is not responding");

			System.exit(0);
		}

		log.info(listWithHits.size() + " hits for the searched term: " + term);
		FileUtils.writeLines(
				new File(Configuration.DATA_PATH.resolve(
						"listsWithHits/" + searchedTerm.replace(" ", "_") + "_listOfHits.txt")
						.toURI()), listWithHits);
		listOfPaths = new ArrayList<>();

		int counterForQueriesRootPath = 0;
		int totalCounterForQueriesRootPath = 0;
		int totalEmptyResponces = 0;
		int threshold = 10;

		for (int i = 0; i < listWithHits.size(); i++) {
			OntologyTerm ot = listWithHits.get(i);

			StringBuffer sb = new StringBuffer();
			StringBuffer sb1 = new StringBuffer();
			sb.append("Found: ");
			sb.append(ot);
			sb.append(";\n Similarity to query: ");
			sb.append(ot.getContext().getSimilarityScore());
			sb.append("%");
			sb.append(";\n Term source: ");
			sb.append(ot.getContext().getServiceType());
			log.debug(sb.toString());

			int similarityScore = ot.getContext().getSimilarityScore();
			if (similarityScore > 90) {

				sb1.append("\n\t\tURI of the hit = ");
				sb1.append(ot.getURI());
				sb1.append("\n\t\tlabel of the hit = ");
				sb1.append(ot.getLabel());
				sb1.append("\n\t\tSource Ontology = ");
				sb1.append(ot.getOntology().getLabel());
				sb1.append("\n\t\tThe term will be processed further, since the similatity score of searched term and hit is greater than 90%.");
				log.info(sb1.toString());

				++counterForQueriesRootPath;
				++totalCounterForQueriesRootPath;

				// log.debug("THE DESCRIPTION OF THE ONTOLOGY:");
				//
				// log.debug(ot.getOntology().getDescription());

				List<Stack<OntologyTerm>> listOfAllPathsForOt = pathExtractor
						.computeAllPaths(ot.getOntology().getAbbreviation(), ot);

				listOfPaths.add(listOfAllPathsForOt);
				
//				for (Stack<OntologyTerm> stack : listOfAllPathsForOt) {
//					System.out.println("+++" + stack);
//				}
//				System.out.println( "counter for empty responses: " + pathExtractor
//							.getCounterForHitsThatDoNotHaveAnyPath());
				// log.info(ot.getURI() + "\t" + ot.getLabel());

				// log.info("getCounterForHitsThatDoNotHaveAnyPath: "
				// + pathExtractor.getCounterForHitsThatDoNotHaveAnyPath());
				// log.info("counterForQueriesRootPath "
				// + counterForQueriesRootPath);

				
				//TODO debug
				if (counterForQueriesRootPath == threshold) {

					int emptyResponces = pathExtractor
							.getCounterForHitsThatDoNotHaveAnyPath();
					totalEmptyResponces += emptyResponces;
					if (emptyResponces > 0) {
						log.debug("do not have any path: " + emptyResponces);

						threshold = emptyResponces;
						// set the counter for empty responses to 0 otherwise to
						// many queries are sent
						pathExtractor.setCounterForHitsThatDoNotHaveAnyPath(0);
						counterForQueriesRootPath = 0;
						continue;
					} else {
						log.debug("all hits have at least one path.");

						break;
					}
				}
			}
			log.debug("__________________________________________________________________________________________");
		}
		log.info("skos:prefLabels= " + pathExtractor.getCounterForPrefLabels());
		log.info("rdfs:labels= " + pathExtractor.getCounterForLabels());

		log.info(totalCounterForQueriesRootPath
				+ " queries for the root path were sent.");

		log.info(totalEmptyResponces + " empty responses from sparql.");

		FileUtils.write(
				new File(Configuration.SPARQL_OUTPUT_PATH.resolve(
						"fail/" + this.date + "_" + term.replace(" ", "_") + "_fail").toURI()),
				pathExtractor.getSbFailed().toString());
		FileUtils.write(
				new File(Configuration.SPARQL_OUTPUT_PATH.resolve(
						"success/" + this.date + "_" + term.replace(" ", "_") + "_success")
						.toURI()), pathExtractor.getSbSuccess().toString());

		log.info("explored paths in total: " + listOfPaths.size());

		return listOfPaths;
	}

	public List<List<Stack<OntologyTerm>>> getListOfPaths() {
		return this.listOfPaths;
	}

	public String getCurrentTerm() {
		return searchedTerm;
	}

	public OntologyService getRestrictedBps() {
		return restrictedBps;
	}
}