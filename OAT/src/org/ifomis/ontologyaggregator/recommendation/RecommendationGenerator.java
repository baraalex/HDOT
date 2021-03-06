package org.ifomis.ontologyaggregator.recommendation;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Stack;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
import org.ifomis.ontologyaggregator.util.Configuration;
import org.ifomis.ontologyaggregator.util.OWLUtilities;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import uk.ac.ebi.ontocat.OntologyService;
import uk.ac.ebi.ontocat.OntologyServiceException;
import uk.ac.ebi.ontocat.OntologyTerm;

/**
 * The RecommendationGenerator generates a recommendation under which concept to
 * integrate a hit found in BioPortal based on the similarity of their paths.
 * 
 * @author Nikolina
 * 
 */
public class RecommendationGenerator {

	private static final Logger log = Logger
			.getLogger(RecommendationGenerator.class);

	// File statisticsFile = new File("data/statistics");
	// FileWriter statistcsWriter = new FileWriter(statisticsFile, true);

	/**
	 * The hdot ontology where the term is going to be inserted as a class.
	 */
	private OWLOntology hdotOntology;

	/**
	 * The manager of the ontology.
	 */
	private OWLOntologyManager ontologyManager;

	/**
	 * the current hit
	 */
	private OntologyTerm currentHit;

	/**
	 * list that stores the parsed hdot hierarchy
	 */
	private List<OWLClass> hierarchyOfHdotClass;

	/**
	 * global counter for parents
	 */
	private int counterForParents;

	/**
	 * The term that the user searches for.
	 */
	private String searchedTerm;

	boolean conceptIdsMatch;
	/**
	 * stores the list of ontologies that are imported in HDOT
	 */
	private List<String> importedOntologies;

	private int hitsCounter;
	private int recommendationCounter;

	private boolean labelsMatch;

	/**
	 * stores the generated recommendations
	 */
	private List<Recommendation> listOfRecommendations;

	/**
	 * the system time by the start of the execution
	 */
	private long start;

	private List<Recommendation> listOfRecsPossibleInCoreOfHDOT;

	private List<Recommendation> listOfInCoreNotLeafMatches;

	private List<Recommendation> listImportedNotLeafMatches;

	private OntologyService ontologyService;

	private Stack<OntologyTerm> hierarchyOfHit;

	private OWLOntology[] sortedHdotModules;

	private int numMatchedParents;

	private Properties properties;

	private boolean matchedClassIsSearchedTerm;

	private OntologyTerm matchedClass;

	/**
	 * A list that contains all classes of the imported ontologies, needed for
	 * HDOT extension constraints.
	 */
	private List<OWLClass> classesOfImportedOntologiesAndCore;

	public OntologyTerm getMatchedClass() {
		return matchedClass;
	}

	public boolean isMatchedClassIsSearchedTerm() {
		return matchedClassIsSearchedTerm;
	}

	/**
	 * Creates a RecommendationGenerator and loads the specified input ontology.
	 * 
	 * @param HDOT_CONTAINER_AUTHORIZED
	 *            the ontology to be loaded
	 * @param ontoOut
	 *            the extended ontology that is saved
	 * @param ontologyService
	 * @throws IOException
	 * @throws URISyntaxException
	 * @throws OntologyServiceException
	 * @throws OWLOntologyCreationException
	 */
	public RecommendationGenerator(String searchedTerm,
			OntologyService ontologyService, long start) throws IOException,
			URISyntaxException, OntologyServiceException,
			OWLOntologyCreationException {

		// initialize the fields
		this.start = start;
		this.ontologyService = ontologyService;

		this.listOfRecommendations = new ArrayList<>();
		this.listOfRecsPossibleInCoreOfHDOT = new ArrayList<>();
		this.listOfInCoreNotLeafMatches = new ArrayList<>();
		this.listImportedNotLeafMatches = new ArrayList<>();
		// this.properties = new Properties();
		// this.properties
		// .load(new FileInputStream("config/aggregator.properties"));

		this.importedOntologies = FileUtils.readLines(new File(
				Configuration.IMPORTED_ONTOLOGIES_FILE.toURI()));
		this.classesOfImportedOntologiesAndCore = new ArrayList<>();

		// Get hold of an ontology manager
		this.ontologyManager = OWLManager.createOWLOntologyManager();
		this.searchedTerm = searchedTerm;

		this.ontologyManager = Configuration
				.mapIrisOfVisibleUserModules(ontologyManager);

		this.ontologyManager = Configuration
				.mapIrisOfUserModulesForCuration(ontologyManager);

		// Now load the local copy of hdot that include all modules
		this.hdotOntology = ontologyManager
				.loadOntologyFromOntologyDocument(Configuration.HDOT_CONTAINER_AUTHORIZED);

		log.info("Loaded ontology: " + hdotOntology);

		List<OWLOntology> hdotModules = ontologyManager
				.getSortedImportsClosure(hdotOntology);

		sortedHdotModules = new ModuleSorter().sortHdotModules(hdotModules);
	}

	/**
	 * Process all five best candidates and generates recommendation for
	 * integration whenever possible.
	 * 
	 * @param listOfPaths
	 *            list of paths for the best 5 hits
	 * @throws URISyntaxException
	 * @throws IOException
	 * @throws OntologyServiceException
	 * @throws OWLOntologyCreationException
	 */
	public int generateRecommendations(
			List<List<Stack<OntologyTerm>>> listOfPathsOfAllHits)
			throws URISyntaxException, IOException, OntologyServiceException,
			OWLOntologyCreationException {

		hitsCounter = 0;

		recommendationCounter = 0;

		// fake value
		int returnValue = -100;

		// loop over hits
		for (List<Stack<OntologyTerm>> listOfPaths : listOfPathsOfAllHits) {
			++hitsCounter;

			// loop over elements of the path
			for (Stack<OntologyTerm> path : listOfPaths) {

				hierarchyOfHit = (Stack<OntologyTerm>) path.clone();

				// the path response was empty
				if (path.size() <= 1) {
					// log.info(path.peek());
					log.info("SPARQL response for the root path was empty");
					continue;
				}
				log.info("\n***************************hit Nr:" + hitsCounter
						+ "***************************");
				log.info("The length of the current path to root is: "
						+ path.size());
				log.debug("____________________________________________________________________");
				counterForParents = 0;

				if (!path.isEmpty()) {
					this.currentHit = path.peek();
					// log.info("current hit=" + this.currentHit);
					printPath(path);

					returnValue = recommend(path);

					if (returnValue == 1) {
						++recommendationCounter;
						// if term has been recommended do not examine next
						// concepts
						break;
					} else if (returnValue == 0) {
						return 0;
					}
					// else if (returnValue == 2) {
					// return 2;
					// }
				} else {
					log.debug("path was empty");
					continue;
				}
				log.info("\n***************************************************************");
			}
		}
		if (recommendationCounter > 0) {
			log.info(recommendationCounter
					+ " RECOMMENDATION(S) WERE GENERATED");
		}
		return returnValue;
	}

	private void printPath(Stack<OntologyTerm> path) {

		for (OntologyTerm ontologyTerm : path) {
			log.info(ontologyTerm.getURI() + "\t" + ontologyTerm.getLabel());
		}
	}

	/**
	 * Process the given path and checks if recommendation can be created.
	 * 
	 * @param path
	 *            the path to root for the current hit that is retrieved from
	 *            BioPortal
	 * @throws URISyntaxException
	 * @throws IOException
	 * @throws OntologyServiceException
	 * @return -1 if recommendation can not be generated, 0 if the term already
	 *         exists in HDOT, 1 if a recommendation can be generated and 2 if
	 *         the term is found in one of the modules for curation.
	 * @throws OWLOntologyCreationException
	 */
	private int recommend(Stack<OntologyTerm> path) throws URISyntaxException,
			IOException, OntologyServiceException, OWLOntologyCreationException {

		OntologyTerm currentCandidate = null;

		while (!path.isEmpty()) {

			// iterate over all ontologies we can use it for modularization
			// in a sorted order

			// for (OWLOntology hdotModule : hdotModules) {
			for (int j = 0; j < sortedHdotModules.length; j++) {

				OWLOntology hdotModule = sortedHdotModules[j];

				currentCandidate = path.peek();

				// exclude owl:Thing
				if (currentCandidate.getURI().toString()
						.equals("http://www.w3.org/2002/07/owl#Thing")) {
					break;
				}

				log.debug("currentCandidate: "
						+ currentCandidate.getURI().toString() + "\t"
						+ currentCandidate.getLabel());

				log.debug("Module: " + hdotModule.getOntologyID());

				// fill in the list with the classes of imported ontologies and
				// those asserted by HDOT_CORE
				if (importedOntologies.contains(hdotModule.getOntologyID()
						.getOntologyIRI().toString())
						|| hdotModule
								.getOntologyID()
								.getOntologyIRI()
								.toString()
								.contains(
										FileUtils.readFileToString(new File(
												Configuration.CORE_MODULE_FILE
														.toURI())))) {
					classesOfImportedOntologiesAndCore.addAll(hdotModule
							.getClassesInSignature());
				}
				
				OntologyTerm matchedConcept = findMatch(currentCandidate,
						hdotModule, false);

				if (matchedConcept != null) {
					// return true in order to terminate
					if (matchedClassIsSearchedTerm) {
						--recommendationCounter;
						return 0;
					}
					path.clear();
					return 1;
					
				} else {

					log.debug("no match found");
					if (counterForParents == 0) {
						boolean matchInCurationModules = checkInModulesForCuration(currentCandidate);
						if (matchInCurationModules) {
							return 2;
						}
					}
				}
				log.debug("____________________________________________________________________");
			}
			// after we checked in all modules pop the current candidate and
			// continue with the next in the next iteration
			path.pop();
			++counterForParents;
		}
		return -1;
	}

	/**
	 * @return true if a match was found in the not authorized container and
	 *         false if not
	 * 
	 * @throws OWLOntologyCreationException
	 * @throws IOException
	 * @throws OntologyServiceException
	 * @throws URISyntaxException
	 */
	private boolean checkInModulesForCuration(OntologyTerm currentCandidate)
			throws OWLOntologyCreationException, URISyntaxException,
			OntologyServiceException, IOException {

		OWLOntology notAuthorizedContainer = ontologyManager
				.loadOntologyFromOntologyDocument(Configuration.HDOT_CONTAINER_NOT_AUTHORIZED);
		Set<OWLOntology> modulesForCuration = notAuthorizedContainer
				.getImports();

		for (OWLOntology moduleForCuration : modulesForCuration) {

			boolean importIsSortedModule = false;
			// check if the import is one of the modules in hdot_all
			for (int i = 0; i < sortedHdotModules.length; i++) {
				OWLOntology sortedModule = sortedHdotModules[i];
				if (sortedModule.getOntologyID().equals(
						moduleForCuration.getOntologyID()))
					importIsSortedModule = true;
			}
			if (importIsSortedModule)
				continue;

			OntologyTerm matchedTerm = findMatch(currentCandidate,
					moduleForCuration, true);

			if (matchedTerm != null) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Builds the recommendation and adds it to the corresponding list.
	 * 
	 * @param hdotModule
	 *            the module where the match was found
	 * @param matchedConcept
	 *            class that matches a parent of the current hit
	 * @param importedFrom
	 * @param termHasBeenRecommended
	 * @return true if termHasBeenRecommended
	 * @throws OntologyServiceException
	 */
	private Recommendation buildRecommendaton(OWLOntology hdotModule,
			OntologyTerm matchedConcept, String importedFrom)
			throws OntologyServiceException {

		List<String> definitions = ontologyService
				.getDefinitions(this.currentHit);
		List<String> synonyms = ontologyService.getSynonyms(this.currentHit);
		List<OntologyTerm> childrenOfHit = ontologyService.getChildren(
				this.currentHit.getOntologyAccession(),
				this.currentHit.getAccession());

		Recommendation recommendation = new Recommendation(hitsCounter,
				currentHit, conceptIdsMatch, labelsMatch, searchedTerm,
				hierarchyOfHdotClass, hierarchyOfHit, hdotOntology, hdotModule,
				counterForParents, matchedConcept, definitions, synonyms,
				childrenOfHit, numMatchedParents, importedFrom);

		return recommendation;
	}

	/**
	 * @param matchedConcept
	 *            the match found in HDOT
	 * @return true if the match is the found concept
	 */
	private boolean isMatchedClassTheSearchedTerm(OntologyTerm matchedConcept) {

		matchedClassIsSearchedTerm = false;
		if (counterForParents == 0) {
			matchedClassIsSearchedTerm = true;

			log.error("\n\n");
			log.error("##############################################################################################################################");
			log.error("\t\t\t\tOAT SHOULD NOT BE EVOKED! Since:");
			log.error("The concept: " + matchedConcept + " already exists.");
			log.error("##############################################################################################################################");
			log.error("\n\n");
		}
		return matchedClassIsSearchedTerm;
	}

	/**
	 * Tries to find a match for the given @link{OntologyTerm} with any class in
	 * the given module.
	 * 
	 * @param currentCandidate
	 *            the candidate term we are looking at
	 * @param currentOntology
	 *            the current ontology we are looking at
	 * @param currentOntologyIsModuleForCuration
	 *            true if the given ontology is a module for curation
	 * 
	 * @return the concept where the term will be integrated under
	 * @throws URISyntaxException
	 * @throws OntologyServiceException
	 * @throws IOException
	 */
	private OntologyTerm findMatch(OntologyTerm currentCandidate,
			OWLOntology currentOntology,
			boolean currentOntologyIsModuleForCuration)
			throws URISyntaxException, OntologyServiceException, IOException {

		numMatchedParents = 1;
		Set<OWLClass> classesInSignature = currentOntology
				.getClassesInSignature();

		OntologyTerm matchedTerm = null;

		// iterate over all classes of the ontology and try to find a match of
		// uri or label
		for (OWLClass hdotClass : classesInSignature) {
			// exclude class Nothing in hdot
			if (hdotClass.isOWLNothing()) {
				continue;
			}
			Set<OWLAnnotation> annotations = hdotClass
					.getAnnotations(currentOntology);

			hierarchyOfHdotClass = new ArrayList<OWLClass>();

			conceptIdsMatch = false;
			labelsMatch = false;

			String pureLabelOfHdotClass = OWLUtilities.retriveRdfsLabel(annotations);

			// compare concept URIs
			conceptIdsMatch = compareIds(currentCandidate.getURI().toString(),
					hdotClass.toStringID());

			if (conceptIdsMatch) {
				log.info("\tCONCEPT IDs of BioPortal HIT and HDOT CLASS MATCH");
				// log.debug("id of current candidate: "
				// + currentCandidate.getURI().toString());
				// log.debug("id of hdot class: " + hdotClass.toStringID());
				// conceptIdsMatch = true;

				// if we find a match then we will retrieve this concept
				matchedTerm = new OntologyTerm();
				matchedTerm.setAccession(hdotClass.toStringID());
			}

			// compare labels
			if (currentCandidate.getLabel().isEmpty()) {
				continue;
			}
			labelsMatch = compareLabelsAndSynonyms(currentCandidate.getLabel()
					.trim(), pureLabelOfHdotClass.trim(), annotations);

			if (labelsMatch) {

				log.info("\tLABELS of BioPortal HIT and HDOT CLASS MATCH");
				// log.info("pureLabelOfHdotClass: " + pureLabelOfHdotClass);
				//
				// log.info("label of currentCandidate: "
				// + currentCandidate.getLabel());
				if (conceptIdsMatch) {
					log.info("\tIDs and LABELS MATCH");
				}
				if (matchedTerm == null) {
					matchedTerm = new OntologyTerm();
				}
				matchedTerm.setLabel(pureLabelOfHdotClass);
			}
			// check after the comparison if a match was found
			// to collect the hierarchy and set the accessions
			if (matchedTerm != null) {
				log.info("_________________________________________________");

				String importedFrom = getImportedFromAnnotation(annotations);
				if (importedFrom.isEmpty())
					importedFrom = currentOntology.getOntologyID()
							.getOntologyIRI().toString();

				matchedTerm.setURI(new URI(hdotClass.toStringID()));
				String[] parts = hdotClass.getIRI().toURI().toString()
						.split("/");
				String accession = parts[parts.length - 2] + ":"
						+ parts[parts.length - 1];
				matchedTerm.setAccession(accession);
				matchedTerm.setLabel(pureLabelOfHdotClass);
				matchedTerm.setOntologyAccession(currentOntology
						.getOntologyID().getOntologyIRI().toString());

				log.info("\tparent of the current hit: "
						+ currentCandidate.getURI().toString() + "\t"
						+ currentCandidate.getLabel());
				log.info("\tmatched class of hdot: "
						+ matchedTerm.getURI().toString() + "\t"
						+ matchedTerm.getLabel());

				matchedClass = matchedTerm;

				boolean matchedClassTheSearchedTerm = false;

				if (!currentOntologyIsModuleForCuration)
					matchedClassTheSearchedTerm = isMatchedClassTheSearchedTerm(matchedTerm);

				extractHierarchyOfMatchedTerm(currentOntology, hdotClass);

				if (matchedClassTheSearchedTerm)
					break;

				countMatchingParents();

				if (!(extensionConditionsAreSatisfied(currentOntology,
						hdotClass, matchedTerm))) {
					matchedTerm = null;
					continue;
				} else {
					listOfRecommendations.add(buildRecommendaton(
							currentOntology, matchedTerm, importedFrom));
				}

				// in case a match was found quit the loop for the classes
				// possibly we can collect the matches in order to have them for
				// further recommendations
				break;
			}

			// otherwise search further
		}
		return matchedTerm;
	}

	private boolean extensionConditionsAreSatisfied(
			OWLOntology currentOntology, OWLClass hdotClass,
			OntologyTerm matchedTerm) throws IOException,
			OntologyServiceException {

		boolean result = true;

		// check if the current class is hdot_core
		boolean isHdotCore = (currentOntology.getOntologyID().getOntologyIRI()
				.toString().contains(FileUtils.readFileToString(new File(
				Configuration.CORE_MODULE_FILE.toURI()))));

		Recommendation recommendation = buildRecommendaton(currentOntology,
				matchedTerm, "");

		// if the match is found in hdot core and the matched class is
		// not a leaf node continue with the next class
		if (isHdotCore) {
			if (!hdotClass.getSubClasses(currentOntology).isEmpty()) {

				log.debug("THE MATCHED CLASS WAS FOUND IN HDOT_CORE BUT IT IS NOT A LEAF NODE");

				// listOfInCoreNotLeafMatches.add(notification);
				listOfInCoreNotLeafMatches.add(recommendation);
				log.debug("search for further matches ...\n");

				result = false;
			}
		}

		// The hdotClass is imported in the current ontology (module)
		if (hdotClass.getSuperClasses(currentOntology).size() == 0) {
			log.debug("THE MATCHED CLASS IS IMPORTED IN THIS MODULE");

			// checks if the imported class is a leaf node
			if (!(hdotClass.getSubClasses(ontologyManager.getOntologies())
					.isEmpty())) {

				// checks if the imported class is contained in the imported
				// ontologies
				if (classesOfImportedOntologiesAndCore.contains(hdotClass)) {

					log.debug("BUT IT IS NOT A LEAF NODE");
					log.debug("search for further matches ...\n");

					listImportedNotLeafMatches.add(recommendation);
					result = false;
				}
				result = true;
			}
		}

		if (importedOntologies.contains(currentOntology.getOntologyID()
				.getOntologyIRI().toString())) {
			log.info("RECOMMENDATION POSSIBLE ONLY IN CORE OF HDOT");
			listOfRecsPossibleInCoreOfHDOT.add(recommendation);
			result = false;
		}

		return result;
	}

	private void countMatchingParents() throws IOException {

		Map<String, String> urisTOLabels = new HashMap<>();

		for (OntologyTerm ontologyTerm : hierarchyOfHit) {
			urisTOLabels.put(ontologyTerm.getURI().toString(), ontologyTerm
					.getLabel().toLowerCase());
		}

		for (OWLClass classInHierarchy : hierarchyOfHdotClass) {
			String uri = classInHierarchy.toStringID();
			// String label =
			// retriveRdfsLabel(classInHierarchy.getAnnotations(currentOntology));
			String label = "";
			// get the labels of the parents to display them
			for (OWLOntology currOnto : hdotOntology.getImports()) {
				if (!classInHierarchy.getAnnotations(currOnto).isEmpty()) {
					label = OWLUtilities.retriveRdfsLabel(classInHierarchy
							.getAnnotations(currOnto));
					break;
				}
			}
			label = label.toLowerCase();

			if (urisTOLabels.containsKey(uri)
					|| (!(label.isEmpty()) && urisTOLabels.containsValue(label))) {
				numMatchedParents++;

			}
		}
		// log.debug("number of matching parents: " + numMatchedParents);
	}

	private boolean compareLabelsAndSynonyms(String currentLabel,
			String pureLabelOfHdotClass, Set<OWLAnnotation> annotations) {

		if (currentLabel.isEmpty() || pureLabelOfHdotClass.isEmpty()) {
			return false;
		}

		boolean result = false;
		if (currentLabel.trim().equalsIgnoreCase(pureLabelOfHdotClass.trim())) {
			result = true;

		} else {
			double similarityOfLabels = DiceCoefficient
					.diceCoefficientOptimized(currentLabel.toLowerCase(),
							pureLabelOfHdotClass.toLowerCase());
			if (similarityOfLabels > 0.95) {

				log.info("\nLABELS of BioPortal HIT and HDOT CLASS SIMILARITY > 95\n");
				// log.info("pureLabelOfHdotClass: " + pureLabelOfHdotClass);
				//
				// log.info("label of currentCandidate: " + currentLabel);
				log.info("similarity of labels: " + similarityOfLabels);
				result = true;
			}
		}
		String[] synonyms = OWLUtilities.retriveHasSynonyms(annotations);
		if (synonyms == null)
			return result;

		for (int i = 0; i < synonyms.length; i++) {
			String synonym = synonyms[i];
			if (currentLabel.trim().equalsIgnoreCase(synonym.trim()))
				return true;
		}

		return result;
	}

	private boolean compareIds(String id1, String id2) {
		return id1.equals(id2);
	}

	/**
	 * Extracts the hierarchy of the given hdot owlClass.
	 * 
	 * @param currentOntology
	 *            the current module we found the match
	 * @param hdotClass
	 *            the @link{OWLClass} that matched one of the parents
	 * @return true if the matched class is imported in the given module
	 */
	private void extractHierarchyOfMatchedTerm(OWLOntology currentOntology,
			OWLClass hdotClass) {

		// extract the hierarchy of this class
		// set the parent to the current class for the first time
		OWLClass parent = hdotClass;

		while (parent != null) {

			Set<OWLClassExpression> superClasses = parent
					.getSuperClasses(ontologyManager.getOntologies());

			OWLClass parent_old = parent;

			// iterate over super classes
			for (OWLClassExpression owlSuperClassExpression : superClasses) {
				if (!this.hierarchyOfHdotClass.contains(parent))
					this.hierarchyOfHdotClass.add(parent);
				if (owlSuperClassExpression.isClassExpressionLiteral()) {
					if (!this.hierarchyOfHdotClass.contains(parent))
						this.hierarchyOfHdotClass.add(parent);

					// asOWLClass can be called only if the class
					// expression is not anonymous
					if (!owlSuperClassExpression.isAnonymous()) {
						parent = owlSuperClassExpression.asOWLClass();
					}

				}
			}

			// due to reflexivity
			if (parent_old.equals(parent)) {
				break;
			}
		}
	}

	

	

	private String getImportedFromAnnotation(Set<OWLAnnotation> annotations) {
		String importedFromAnnotation = "";
		for (OWLAnnotation owlAnnotation : annotations) {

			// get just the rdfs: label annotations
			if (owlAnnotation.toString().contains("IAO_0000412")) {
				if (owlAnnotation.getValue().toString().split("\"").length > 1)
					importedFromAnnotation = owlAnnotation.getValue()
							.toString().split("\"")[1];
			}
		}

		return importedFromAnnotation;
	}

	public List<Recommendation> getListOfRecommendations() {
		return listOfRecommendations;
	}

	public List<Recommendation> getListOfRecsPossibleInCoreOfHDOT() {
		return listOfRecsPossibleInCoreOfHDOT;
	}

	public List<Recommendation> getListOfInCoreNotLeafMatches() {
		return listOfInCoreNotLeafMatches;
	}

	public List<Recommendation> getListImportedNotLeafMatches() {
		return listImportedNotLeafMatches;
	}

	public OWLOntologyManager getOntology_manager() {
		return ontologyManager;
	}

	public OWLOntology getHdot_ontology() {
		return hdotOntology;
	}

	public OntologyService getOntologyService() {
		return ontologyService;
	}

	public List<OntologyTerm> getHierarchyOfHdotClass() {
		List<OntologyTerm> result = new ArrayList<>();

		for (int i = hierarchyOfHdotClass.size() - 1; i >= 0; i--) {
			OWLClass entryOfHierarchy = hierarchyOfHdotClass.get(i);
			OntologyTerm ot = new OntologyTerm();
			ot.setURI(entryOfHierarchy.getIRI().toURI());
			String[] parts = entryOfHierarchy.getIRI().toURI().toString()
					.split("/");
			String accession = parts[parts.length - 2] + ":"
					+ parts[parts.length - 1];
			ot.setAccession(accession);
			String label = "";
			// get the label of the OWLClass to set them in the ot
			for (OWLOntology currOnto : hdotOntology.getImports()) {
				if (!entryOfHierarchy.getAnnotations(currOnto).isEmpty()) {
					label = OWLUtilities.retriveRdfsLabel(entryOfHierarchy
							.getAnnotations(currOnto));
					ot.setOntologyAccession(currOnto.getOntologyID()
							.getOntologyIRI().toString());
					break;
				}
			}
			ot.setLabel(label);
			result.add(ot);
		}
		return result;
	}

	public boolean hasHierarchyOfHdotClass() {
		return hierarchyOfHdotClass != null;
	}
}