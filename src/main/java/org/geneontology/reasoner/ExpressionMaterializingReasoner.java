package org.geneontology.reasoner;


import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;
import org.semanticweb.elk.owlapi.ElkReasoner;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDataPropertyExpression;
import org.semanticweb.owlapi.model.OWLDisjointClassesAxiom;
import org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLObjectPropertyExpression;
import org.semanticweb.owlapi.model.OWLObjectPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLObjectSomeValuesFrom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.model.SetOntologyID;
import org.semanticweb.owlapi.model.UnknownOWLOntologyException;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.reasoner.AxiomNotInProfileException;
import org.semanticweb.owlapi.reasoner.BufferingMode;
import org.semanticweb.owlapi.reasoner.ClassExpressionNotInProfileException;
import org.semanticweb.owlapi.reasoner.FreshEntitiesException;
import org.semanticweb.owlapi.reasoner.FreshEntityPolicy;
import org.semanticweb.owlapi.reasoner.InconsistentOntologyException;
import org.semanticweb.owlapi.reasoner.IndividualNodeSetPolicy;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.Node;
import org.semanticweb.owlapi.reasoner.NodeSet;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerConfiguration;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.semanticweb.owlapi.reasoner.ReasonerInterruptedException;
import org.semanticweb.owlapi.reasoner.SimpleConfiguration;
import org.semanticweb.owlapi.reasoner.TimeOutException;
import org.semanticweb.owlapi.reasoner.UnsupportedEntailmentTypeException;
import org.semanticweb.owlapi.reasoner.impl.OWLReasonerBase;
import org.semanticweb.owlapi.util.Version;

import com.google.common.base.Optional;

/**
 * This wraps an existing reasoner to implement OWLExtendedReasoner.
 * 
 * It works by materializing expressions of the form "R some Y" as
 * equivalence axioms prior to reasoning.
 * 
 * After reasoning, it can retrieve these anonymous superclasses.
 * 
 * Currently limited to a single level of nesting - in principle it could be extended
 * to expressions of depth k
 * 
 * In terms of performance the biggest impact are the number of {@link OWLObjectProperty} 
 * for which the materialization is required.
 * 
 * It is usually *NOT* recommended to use all properties of an ontology signature.
 * Instead call materializeExpressions
 * 
 * @author cjm
 *
 */
public class ExpressionMaterializingReasoner extends OWLReasonerBase implements OWLExtendedReasoner {

    private static Logger LOG = Logger.getLogger(ExpressionMaterializingReasoner.class);
	private OWLReasoner wrappedReasoner;

	private final OWLDataFactory dataFactory;
	private final OWLOntology rootOntology;
	private final OWLOntology expandedOntology;
	final OWLOntologyManager manager;
	final Set<OWLObjectProperty> cachedProperties;
	
	// map between materialized named classes and their class expression equivalent
    private final Map<OWLClass,OWLObjectSomeValuesFrom> cxMap;
    private final Map<OWLObjectSomeValuesFrom, OWLClass> cxMapRev;
	
	private boolean includeImports = false;


	protected ExpressionMaterializingReasoner(OWLOntology rootOntology, 
			OWLReasonerFactory reasonerFactory,
			OWLReasonerConfiguration configuration, BufferingMode bufferingMode) {
		super(rootOntology, configuration, bufferingMode);
		try {
			this.rootOntology = rootOntology;
			manager = rootOntology.getOWLOntologyManager();
			dataFactory = manager.getOWLDataFactory();
			expandedOntology = createExpandedOntologyStub(rootOntology);
			if (BufferingMode.NON_BUFFERING == bufferingMode) {
				wrappedReasoner = reasonerFactory.createReasoner(expandedOntology, configuration);	
			}
			else {
				wrappedReasoner = reasonerFactory.createNonBufferingReasoner(expandedOntology, configuration);
			}
			
		} catch (UnknownOWLOntologyException e) {
			throw new RuntimeException("Could not setup reasoner", e);
		} catch (OWLOntologyCreationException e) {
			throw new RuntimeException("Could not setup reasoner", e);
		}
		cachedProperties = new HashSet<OWLObjectProperty>();
        cxMap = new HashMap<OWLClass, OWLObjectSomeValuesFrom>();
        cxMapRev = new HashMap<OWLObjectSomeValuesFrom, OWLClass>();
        
        // we can consider making this optional, but in general it seems to have 
        // no drawback in making inference slower
        if (wrappedReasoner instanceof ElkReasoner) {
            rewriteRangeAxioms();
        }
	}
	


    /**
     * Range axioms are very useful for curtailing the space of possible SVF expressions.
     * E.g. forbid occurs-in SOME apoptosis
     * 
     * Elk does not support Range axioms, so we use the range axioms to generate
     * additional axioms that while not complete give us many benefits.
     * 
     * E.g. if we have:
     *  - Range(occurs-in, material-entity)
     *  - material-entity SubClassOf* continuant
     *  - continuant DisjointWith occurrent
     *  
     *   then we can add an axiom
     *   Nothing = occurs-in some continuant
     * 
     * Adding these do not appear to increase overall time for ontologies such as go-plus,
     * and overall running is faster due to less expressions being generated.
     * 
     * 
     * 
     */
    public void rewriteRangeAxioms() {
	    OWLClass nothing = dataFactory.getOWLNothing();
	    Set<OWLAxiom> newAxioms = new HashSet<>();
        Set<OWLObjectPropertyRangeAxiom> raxs = new HashSet<>();
        Map<OWLClassExpression, Set<OWLClassExpression>> disjointMap = new HashMap<>();
        for (OWLAxiom ax : rootOntology.getAxioms(Imports.INCLUDED)) {
            if (ax instanceof OWLDisjointClassesAxiom) {
                OWLDisjointClassesAxiom da = (OWLDisjointClassesAxiom)ax;
                for (OWLClassExpression c1 : da.getClassExpressions()) {
                    if (!disjointMap.containsKey(c1))
                        disjointMap.put(c1, new HashSet<>());
                    for (OWLClassExpression c2 : da.getClassExpressionsMinus(c1)) {
                        disjointMap.get(c1).add(c2);
                    }
                    
                }
            }
        }
	    for (OWLAxiom ax : rootOntology.getAxioms(Imports.INCLUDED)) {
	        if (ax instanceof OWLObjectPropertyRangeAxiom) {
	            OWLObjectPropertyRangeAxiom rax = (OWLObjectPropertyRangeAxiom)ax; 
	            raxs.add(rax);
	            OWLClassExpression rc = rax.getRange();
	            // elk does not support disjoint classes, so we follow hierarchy
	            Set<OWLClassExpression> disjointClasses = new HashSet<>();
	            Set<OWLClass> rscs = 
	                    wrappedReasoner.getSuperClasses(rc, false).getFlattened();
	            for (OWLClass rsc : rscs) {
	                if (disjointMap.containsKey(rsc))
	                    disjointClasses.addAll(disjointMap.get(rsc));
	            }
	            if (disjointMap.containsKey(rc)) {
	                disjointClasses.addAll(disjointMap.get(rc));
	            }
	            
                for (OWLClassExpression d : disjointClasses) {
	                OWLEquivalentClassesAxiom cax = dataFactory.getOWLEquivalentClassesAxiom(nothing,
	                        dataFactory.getOWLObjectSomeValuesFrom(rax.getProperty(), d));
	                newAxioms.add(cax);
	                LOG.info("Translated "+rax+" --> "+cax);
	            }
	        }
	    }
	    expandedOntology.getOWLOntologyManager().addAxioms(expandedOntology, newAxioms);
	}

	/**
	 * @param rootOntology
	 * @return ontology
	 * @throws OWLOntologyCreationException
	 */
	private OWLOntology createExpandedOntologyStub(OWLOntology rootOntology)
			throws OWLOntologyCreationException {
		OWLOntology expandedOntology = manager.createOntology(IRI.generateDocumentIRI());
		IRI rootOntologyIRI;
		OWLOntologyID rootId = rootOntology.getOntologyID();
		if (rootId == null) {
			rootOntologyIRI = IRI.generateDocumentIRI();
			manager.applyChange(new SetOntologyID(rootOntology, rootOntologyIRI));
		}
		else {
			Optional<IRI> optional = rootId.getOntologyIRI();
			if (optional.isPresent() == false) {
				rootOntologyIRI = IRI.generateDocumentIRI();
				manager.applyChange(new SetOntologyID(rootOntology, rootOntologyIRI));
			}
			else {
				rootOntologyIRI = optional.get();
			}
		}
		AddImport ai = new AddImport(expandedOntology, 
				dataFactory.getOWLImportsDeclaration(rootOntologyIRI));
		manager.applyChange(ai);
		
		return expandedOntology;
	}

	public ExpressionMaterializingReasoner(OWLOntology ont, OWLReasonerFactory reasonerFactory) {
		this(ont, reasonerFactory, new SimpleConfiguration(), BufferingMode.BUFFERING);
	}
	
	public ExpressionMaterializingReasoner(OWLOntology ont, OWLReasonerFactory reasonerFactory, BufferingMode bufferingMode) {
		this(ont, reasonerFactory, new SimpleConfiguration(), bufferingMode);
	}

	public OWLReasoner getWrappedReasoner() {
		return wrappedReasoner;
	}

	/**
	 * @param includeImports
	 */
	public void setIncludeImports(boolean includeImports) {
		this.includeImports = includeImports;
	}
	
	/**
	 * @return boolean
	 */
	public boolean isIncludeImports() {
		return includeImports;
	}
	
	/**
	 * Materialize expressions for all classes and properties in the ontology signature.
	 * 
	 * @see ExpressionMaterializingReasoner#setIncludeImports(boolean) if it should include imports
	 */
	public void materializeExpressions() {
	    LOG.info("Materializing SVFs for ALL properties");
		materializeExpressions(rootOntology.getObjectPropertiesInSignature(Imports.fromBoolean(includeImports)));
	}

	/**
	 * Materialize expressions for a collection of properties and all classes in the ontology signature.
	 * 
	 * @param properties
	 * @see ExpressionMaterializingReasoner#setIncludeImports(boolean) if it should include imports
	 */
	public void materializeExpressions(Collection<OWLObjectProperty> properties) {
        LOG.info("Materializing SVFs for: "+properties);
		for (OWLObjectProperty p : properties) {
			if (cachedProperties.contains(p)){
				continue;
			}
			materializeExpressions(p);
		}
		flush();
	}

	/**
	 * Materialize expressions a property and all classes in the ontology signature.
	 * 
	 * @param p
	 * @see ExpressionMaterializingReasoner#setIncludeImports(boolean) if it should include imports
	 */
	public void materializeExpressions(OWLObjectProperty p) {
		if (cachedProperties.contains(p))
			return;
		materializeExpressionsInternal(p);
		flush();
	}
	
	private void materializeExpressionsInternal(OWLObjectProperty p) {
        LOG.info("Materializing SVFs for: "+p);

		for (OWLClass baseClass : rootOntology.getClassesInSignature(Imports.fromBoolean(includeImports))) {
			// only materialize for non-helper classes
			if (cxMap.containsKey(baseClass)) {
				continue;
			}
			OWLObjectSomeValuesFrom x = dataFactory.getOWLObjectSomeValuesFrom(p, baseClass);
			IRI xciri = IRI.create(baseClass.getIRI()+"__"+saveIRItoString(p.getIRI()));
			OWLClass namedClassEquivalent = dataFactory.getOWLClass(xciri);
			OWLEquivalentClassesAxiom eca = dataFactory.getOWLEquivalentClassesAxiom(namedClassEquivalent, x);
			String lbl = p.getIRI().getShortForm()+" "+baseClass.getIRI().getShortForm();
			manager.addAxiom(expandedOntology, dataFactory.getOWLAnnotationAssertionAxiom(xciri, dataFactory.getOWLAnnotation(dataFactory.getRDFSLabel(), dataFactory.getOWLLiteral(lbl))));
            cxMap.put(namedClassEquivalent, x);
            cxMapRev.put(x, namedClassEquivalent);
			manager.addAxiom(expandedOntology, eca);
			manager.addAxiom(expandedOntology, dataFactory.getOWLDeclarationAxiom(namedClassEquivalent));
		}
		cachedProperties.add(p);
	}
	
	private CharSequence saveIRItoString(IRI iri) {
		StringBuilder sb = new StringBuilder();
		String s = iri.toString();
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == ':' || c == '/') {
				c = '_';
			}
			sb.append(c);
		}
		return sb;
	}

	public Set<OWLClassExpression> getSuperClassExpressions(OWLClassExpression ce,
			boolean direct) throws InconsistentOntologyException,
			ClassExpressionNotInProfileException, FreshEntitiesException,
			ReasonerInterruptedException, TimeOutException {

		Set<OWLClassExpression> ces = new HashSet<OWLClassExpression>();
		wrappedReasoner.flush();
		for (OWLClass c : wrappedReasoner.getSuperClasses(ce, direct).getFlattened()) {
			if (cxMap.containsKey(c)) {
				ces.add(cxMap.get(c));
			}
			else {
				ces.add(c);
			}
		}
		return ces;
	}
	
	public Set<OWLObjectSomeValuesFrom> getSomeValuesFromSuperClasses(OWLClass baseClass,
	        OWLObjectProperty p,
	        boolean direct) throws InconsistentOntologyException,
	ClassExpressionNotInProfileException, FreshEntitiesException,
	ReasonerInterruptedException, TimeOutException {
	   return getSomeValuesFromSuperClasses(baseClass, p, direct, false);
	}

	public Set<OWLObjectSomeValuesFrom> getSomeValuesFromSuperClasses(OWLClass baseClass,
	        OWLObjectProperty p,
	        boolean direct, boolean reflexive) throws InconsistentOntologyException,
	ClassExpressionNotInProfileException, FreshEntitiesException,
	ReasonerInterruptedException, TimeOutException {

	    Set<OWLObjectSomeValuesFrom> ces = new HashSet<>();
	    wrappedReasoner.flush();
	    
	    OWLObjectSomeValuesFrom svf = dataFactory.getOWLObjectSomeValuesFrom(p, baseClass);
	    OWLClassExpression queryExpression = svf;
	    // for efficiency, used a generated named class if available.
	    // Explanation: Elk is highly inefficient if queried using a class expression, as
	    // it needs to generate a new class and test with this. It's unneccessary to force Elk to
	    // do this, if we already have a NC equivalent for the query expression
	    // TODO: make a PR on Elk code
	    if (cxMapRev.containsKey(svf)) {
	        queryExpression = cxMapRev.get(svf);
	    }
	    if (!wrappedReasoner.isSatisfiable(queryExpression)) {
	        return ces;
	    }
	    for (OWLClass c : wrappedReasoner.getSuperClasses(queryExpression, direct).getFlattened()) {
	        if (cxMap.containsKey(c)) {
	            OWLObjectSomeValuesFrom cx = cxMap.get(c);
	            ces.add(cx);
	        }
	    }
	    if (reflexive) {
	        ces.add(svf);
	    }
	    return ces;
	}
	
	
	public Set<OWLSubClassOfAxiom> getSomeValuesFromSubsumptions(OWLObjectProperty p) throws InconsistentOntologyException,
	ClassExpressionNotInProfileException, FreshEntitiesException,
	ReasonerInterruptedException, TimeOutException {
	    Set<OWLSubClassOfAxiom> axioms = new HashSet<>();
	    int n=0;
	    Set<OWLClass> allClasses = rootOntology.getClassesInSignature(Imports.INCLUDED);
	    for (OWLClass c : allClasses) {
	        n++;
	        if (n % 1000 == 0) {
	            LOG.info("Class "+n+"/"+allClasses.size());
	        }
	        for (OWLObjectSomeValuesFrom sc : getSomeValuesFromSuperClasses(c, p, false, true)) {
	            axioms.add(dataFactory.getOWLSubClassOfAxiom(
	                    dataFactory.getOWLObjectSomeValuesFrom(p, c),
	                    sc));
	        }
	    }
        return axioms;
	}
	
	public Set<OWLSubClassOfAxiom> getSomeValuesFromSubsumptions() throws InconsistentOntologyException,
	ClassExpressionNotInProfileException, FreshEntitiesException,
	ReasonerInterruptedException, TimeOutException {
	    Set<OWLSubClassOfAxiom> axioms = new HashSet<>();
	    for (OWLObjectProperty p : rootOntology.getObjectPropertiesInSignature()) {
	        LOG.info("Calculating svf subsumptions for "+p);
	        axioms.addAll(getSomeValuesFromSubsumptions(p));
	    }
	    return axioms;
	}
	
	public Set<OWLSubClassOfAxiom> getSomeValuesFromSubsumptions(Set<OWLObjectProperty> props) throws InconsistentOntologyException,
	ClassExpressionNotInProfileException, FreshEntitiesException,
	ReasonerInterruptedException, TimeOutException {
	    Set<OWLSubClassOfAxiom> axioms = new HashSet<>();
	    if (props == null)
	        props = rootOntology.getObjectPropertiesInSignature();
	    for (OWLObjectProperty p : props) {
	        LOG.info("Calculating svf subsumptions for "+p);
	        axioms.addAll(getSomeValuesFromSubsumptions(p));
	    }
	    return axioms;

	}

	public Set<OWLClass> getSuperClassesOver(OWLClassExpression ce,
			OWLObjectProperty p,
			boolean direct) throws InconsistentOntologyException,
			ClassExpressionNotInProfileException, FreshEntitiesException,
			ReasonerInterruptedException, TimeOutException {

		materializeExpressions(p);
		Set<OWLClass> nxs = new HashSet<OWLClass>(); // named expressions
		for (OWLClass c : wrappedReasoner.getSuperClasses(ce, false).getFlattened()) {
			if (cxMap.containsKey(c)) {
				OWLObjectSomeValuesFrom x = cxMap.get(c);
				if (x.getProperty().equals(p)) {
					nxs.add(c);
				}
			}
		}
		if (direct) {
			Set<OWLClass> ics = new HashSet<OWLClass>();
			for (OWLClass c : nxs) {
				for (OWLClass sc : wrappedReasoner.getSuperClasses(c, false).getFlattened()) {
					if (nxs.contains(sc)) {
						ics.add(sc);
					}
				}				
			}
			nxs.removeAll(ics);
		}
		Set<OWLClass> rcs = new HashSet<OWLClass>();
		for (OWLClass c : nxs) {
			OWLObjectSomeValuesFrom x = cxMap.get(c);
			OWLClass v = (OWLClass) x.getFiller();
			rcs.add(v);
		}
		return rcs;
	}


	public String getReasonerName() {
		return "Expression Materializing Reasoner";
	}

	public Version getReasonerVersion() {
		return wrappedReasoner.getReasonerVersion();
	}

	public BufferingMode getBufferingMode() {
		return wrappedReasoner.getBufferingMode();
	}

	public void flush() {
		wrappedReasoner.flush();
	}

	public List<OWLOntologyChange> getPendingChanges() {
		return wrappedReasoner.getPendingChanges();
	}

	public Set<OWLAxiom> getPendingAxiomAdditions() {
		return wrappedReasoner.getPendingAxiomAdditions();
	}

	public Set<OWLAxiom> getPendingAxiomRemovals() {
		return wrappedReasoner.getPendingAxiomRemovals();
	}

	public OWLOntology getRootOntology() {
		return wrappedReasoner.getRootOntology();
	}

	public void interrupt() {
		wrappedReasoner.interrupt();
	}

	public void precomputeInferences(InferenceType... inferenceTypes)
			throws ReasonerInterruptedException, TimeOutException,
			InconsistentOntologyException {
		wrappedReasoner.precomputeInferences(inferenceTypes);
	}

	public boolean isPrecomputed(InferenceType inferenceType) {
		return wrappedReasoner.isPrecomputed(inferenceType);
	}

	public Set<InferenceType> getPrecomputableInferenceTypes() {
		return wrappedReasoner.getPrecomputableInferenceTypes();
	}

	public boolean isConsistent() throws ReasonerInterruptedException,
	TimeOutException {
		return wrappedReasoner.isConsistent();
	}

	public boolean isSatisfiable(OWLClassExpression classExpression)
			throws ReasonerInterruptedException, TimeOutException,
			ClassExpressionNotInProfileException, FreshEntitiesException,
			InconsistentOntologyException {
		return wrappedReasoner.isSatisfiable(classExpression);
	}

	public Node<OWLClass> getUnsatisfiableClasses()
			throws ReasonerInterruptedException, TimeOutException,
			InconsistentOntologyException {
		return wrappedReasoner.getUnsatisfiableClasses();
	}

	public boolean isEntailed(OWLAxiom axiom)
			throws ReasonerInterruptedException,
			UnsupportedEntailmentTypeException, TimeOutException,
			AxiomNotInProfileException, FreshEntitiesException,
			InconsistentOntologyException {
		return wrappedReasoner.isEntailed(axiom);
	}

	public boolean isEntailed(Set<? extends OWLAxiom> axioms)
			throws ReasonerInterruptedException,
			UnsupportedEntailmentTypeException, TimeOutException,
			AxiomNotInProfileException, FreshEntitiesException,
			InconsistentOntologyException {
		return wrappedReasoner.isEntailed(axioms);
	}

	public boolean isEntailmentCheckingSupported(AxiomType<?> axiomType) {
		return wrappedReasoner.isEntailmentCheckingSupported(axiomType);
	}

	public Node<OWLClass> getTopClassNode() {
		return wrappedReasoner.getTopClassNode();
	}

	public Node<OWLClass> getBottomClassNode() {
		return wrappedReasoner.getBottomClassNode();
	}

	public NodeSet<OWLClass> getSubClasses(OWLClassExpression ce, boolean direct)
			throws ReasonerInterruptedException, TimeOutException,
			FreshEntitiesException, InconsistentOntologyException,
			ClassExpressionNotInProfileException {
		return wrappedReasoner.getSubClasses(ce, direct);
	}

	public NodeSet<OWLClass> getSuperClasses(OWLClassExpression ce,
			boolean direct) throws InconsistentOntologyException,
			ClassExpressionNotInProfileException, FreshEntitiesException,
			ReasonerInterruptedException, TimeOutException {
		return wrappedReasoner.getSuperClasses(ce, direct);
	}

	public Node<OWLClass> getEquivalentClasses(OWLClassExpression ce)
			throws InconsistentOntologyException,
			ClassExpressionNotInProfileException, FreshEntitiesException,
			ReasonerInterruptedException, TimeOutException {
		return wrappedReasoner.getEquivalentClasses(ce);
	}

	public NodeSet<OWLClass> getDisjointClasses(OWLClassExpression ce)
			throws ReasonerInterruptedException, TimeOutException,
			FreshEntitiesException, InconsistentOntologyException {
		return wrappedReasoner.getDisjointClasses(ce);
	}

	public Node<OWLObjectPropertyExpression> getTopObjectPropertyNode() {
		return wrappedReasoner.getTopObjectPropertyNode();
	}

	public Node<OWLObjectPropertyExpression> getBottomObjectPropertyNode() {
		return wrappedReasoner.getBottomObjectPropertyNode();
	}

	public NodeSet<OWLObjectPropertyExpression> getSubObjectProperties(
			OWLObjectPropertyExpression pe, boolean direct)
					throws InconsistentOntologyException, FreshEntitiesException,
					ReasonerInterruptedException, TimeOutException {
		return wrappedReasoner.getSubObjectProperties(pe, direct);
	}

	public NodeSet<OWLObjectPropertyExpression> getSuperObjectProperties(
			OWLObjectPropertyExpression pe, boolean direct)
					throws InconsistentOntologyException, FreshEntitiesException,
					ReasonerInterruptedException, TimeOutException {
		return wrappedReasoner.getSuperObjectProperties(pe, direct);
	}

	public Node<OWLObjectPropertyExpression> getEquivalentObjectProperties(
			OWLObjectPropertyExpression pe)
					throws InconsistentOntologyException, FreshEntitiesException,
					ReasonerInterruptedException, TimeOutException {
		return wrappedReasoner.getEquivalentObjectProperties(pe);
	}

	public NodeSet<OWLObjectPropertyExpression> getDisjointObjectProperties(
			OWLObjectPropertyExpression pe)
					throws InconsistentOntologyException, FreshEntitiesException,
					ReasonerInterruptedException, TimeOutException {
		return wrappedReasoner.getDisjointObjectProperties(pe);
	}

	public Node<OWLObjectPropertyExpression> getInverseObjectProperties(
			OWLObjectPropertyExpression pe)
					throws InconsistentOntologyException, FreshEntitiesException,
					ReasonerInterruptedException, TimeOutException {
		return wrappedReasoner.getInverseObjectProperties(pe);
	}

	public NodeSet<OWLClass> getObjectPropertyDomains(
			OWLObjectPropertyExpression pe, boolean direct)
					throws InconsistentOntologyException, FreshEntitiesException,
					ReasonerInterruptedException, TimeOutException {
		return wrappedReasoner.getObjectPropertyDomains(pe, direct);
	}

	public NodeSet<OWLClass> getObjectPropertyRanges(
			OWLObjectPropertyExpression pe, boolean direct)
					throws InconsistentOntologyException, FreshEntitiesException,
					ReasonerInterruptedException, TimeOutException {
		return wrappedReasoner.getObjectPropertyRanges(pe, direct);
	}

	public Node<OWLDataProperty> getTopDataPropertyNode() {
		return wrappedReasoner.getTopDataPropertyNode();
	}

	public Node<OWLDataProperty> getBottomDataPropertyNode() {
		return wrappedReasoner.getBottomDataPropertyNode();
	}

	public NodeSet<OWLDataProperty> getSubDataProperties(OWLDataProperty pe,
			boolean direct) throws InconsistentOntologyException,
			FreshEntitiesException, ReasonerInterruptedException,
			TimeOutException {
		return wrappedReasoner.getSubDataProperties(pe, direct);
	}

	public NodeSet<OWLDataProperty> getSuperDataProperties(OWLDataProperty pe,
			boolean direct) throws InconsistentOntologyException,
			FreshEntitiesException, ReasonerInterruptedException,
			TimeOutException {
		return wrappedReasoner.getSuperDataProperties(pe, direct);
	}

	public Node<OWLDataProperty> getEquivalentDataProperties(OWLDataProperty pe)
			throws InconsistentOntologyException, FreshEntitiesException,
			ReasonerInterruptedException, TimeOutException {
		return wrappedReasoner.getEquivalentDataProperties(pe);
	}

	public NodeSet<OWLDataProperty> getDisjointDataProperties(
			OWLDataPropertyExpression pe) throws InconsistentOntologyException,
			FreshEntitiesException, ReasonerInterruptedException,
			TimeOutException {
		return wrappedReasoner.getDisjointDataProperties(pe);
	}

	public NodeSet<OWLClass> getDataPropertyDomains(OWLDataProperty pe,
			boolean direct) throws InconsistentOntologyException,
			FreshEntitiesException, ReasonerInterruptedException,
			TimeOutException {
		return wrappedReasoner.getDataPropertyDomains(pe, direct);
	}

	public NodeSet<OWLClass> getTypes(OWLNamedIndividual ind, boolean direct)
			throws InconsistentOntologyException, FreshEntitiesException,
			ReasonerInterruptedException, TimeOutException {
		return wrappedReasoner.getTypes(ind, direct);
	}

	public NodeSet<OWLNamedIndividual> getInstances(OWLClassExpression ce,
			boolean direct) throws InconsistentOntologyException,
			ClassExpressionNotInProfileException, FreshEntitiesException,
			ReasonerInterruptedException, TimeOutException {
		return wrappedReasoner.getInstances(ce, direct);
	}

	public NodeSet<OWLNamedIndividual> getObjectPropertyValues(
			OWLNamedIndividual ind, OWLObjectPropertyExpression pe)
					throws InconsistentOntologyException, FreshEntitiesException,
					ReasonerInterruptedException, TimeOutException {
		return wrappedReasoner.getObjectPropertyValues(ind, pe);
	}

	public Set<OWLLiteral> getDataPropertyValues(OWLNamedIndividual ind,
			OWLDataProperty pe) throws InconsistentOntologyException,
			FreshEntitiesException, ReasonerInterruptedException,
			TimeOutException {
		return wrappedReasoner.getDataPropertyValues(ind, pe);
	}

	public Node<OWLNamedIndividual> getSameIndividuals(OWLNamedIndividual ind)
			throws InconsistentOntologyException, FreshEntitiesException,
			ReasonerInterruptedException, TimeOutException {
		return wrappedReasoner.getSameIndividuals(ind);
	}

	public NodeSet<OWLNamedIndividual> getDifferentIndividuals(
			OWLNamedIndividual ind) throws InconsistentOntologyException,
			FreshEntitiesException, ReasonerInterruptedException,
			TimeOutException {
		return wrappedReasoner.getDifferentIndividuals(ind);
	}

	public long getTimeOut() {
		return wrappedReasoner.getTimeOut();
	}

	public FreshEntityPolicy getFreshEntityPolicy() {
		return wrappedReasoner.getFreshEntityPolicy();
	}

	public IndividualNodeSetPolicy getIndividualNodeSetPolicy() {
		return wrappedReasoner.getIndividualNodeSetPolicy();
	}

	public void dispose() {
		wrappedReasoner.dispose();
	}

	@Override
	protected void handleChanges(Set<OWLAxiom> addAxioms,
			Set<OWLAxiom> removeAxioms) {
		// this is intentionally empty
		/*
		 * the changes should be handled by the original reasoner already,
		 * as it should be a listener of the same ontology manager.
		 */

	}


}
