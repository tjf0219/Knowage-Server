package it.eng.spagobi.engines.qbe.api;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.StreamingOutput;

import org.apache.log4j.Logger;
import org.jgrapht.Graph;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONObjectDeserializator;

import com.jamonapi.Monitor;
import com.jamonapi.MonitorFactory;

import it.eng.qbe.dataset.FederatedDataSet;
import it.eng.qbe.dataset.QbeDataSet;
import it.eng.qbe.model.accessmodality.IModelAccessModality;
import it.eng.qbe.model.structure.IModelEntity;
import it.eng.qbe.model.structure.IModelField;
import it.eng.qbe.model.structure.IModelStructure;
import it.eng.qbe.query.HavingField;
import it.eng.qbe.query.IQueryField;
import it.eng.qbe.query.Query;
import it.eng.qbe.query.TimeAggregationHandler;
import it.eng.qbe.query.WhereField;
import it.eng.qbe.query.filters.SqlFilterModelAccessModality;
import it.eng.qbe.query.serializer.SerializerFactory;
import it.eng.qbe.serializer.SerializationException;
import it.eng.qbe.statement.AbstractQbeDataSet;
import it.eng.qbe.statement.IStatement;
import it.eng.qbe.statement.QbeDatasetFactory;
import it.eng.qbe.statement.graph.GraphManager;
import it.eng.qbe.statement.graph.bean.QueryGraph;
import it.eng.qbe.statement.graph.bean.Relationship;
import it.eng.qbe.statement.graph.bean.RootEntitiesGraph;
import it.eng.qbe.statement.hibernate.HQLDataSet;
import it.eng.qbe.statement.jpa.JPQLDataSet;
import it.eng.spago.base.SourceBean;
import it.eng.spagobi.commons.bo.UserProfile;
import it.eng.spagobi.commons.constants.SpagoBIConstants;
import it.eng.spagobi.engines.qbe.QbeEngineConfig;
import it.eng.spagobi.services.common.SsoServiceInterface;
import it.eng.spagobi.services.proxy.DataSetServiceProxy;
import it.eng.spagobi.services.rest.annotations.ManageAuthorization;
import it.eng.spagobi.services.rest.annotations.UserConstraint;
import it.eng.spagobi.tools.dataset.bo.DataSetParametersList;
import it.eng.spagobi.tools.dataset.bo.IDataSet;
import it.eng.spagobi.tools.dataset.common.datastore.IDataStore;
import it.eng.spagobi.tools.dataset.common.datawriter.JSONDataWriter;
import it.eng.spagobi.tools.dataset.common.iterator.CsvStreamingOutput;
import it.eng.spagobi.tools.dataset.common.iterator.DataIterator;
import it.eng.spagobi.tools.dataset.common.metadata.IFieldMetaData;
import it.eng.spagobi.tools.dataset.common.metadata.IMetaData;
import it.eng.spagobi.tools.dataset.constants.DataSetConstants;
import it.eng.spagobi.tools.dataset.federation.FederationDefinition;
import it.eng.spagobi.tools.dataset.utils.DataSetUtilities;
import it.eng.spagobi.tools.dataset.utils.DatasetMetadataParser;
import it.eng.spagobi.tools.datasource.bo.IDataSource;
import it.eng.spagobi.utilities.assertion.Assert;
import it.eng.spagobi.utilities.engines.EngineConstants;
import it.eng.spagobi.utilities.engines.SpagoBIEngineServiceException;
import it.eng.spagobi.utilities.exceptions.SpagoBIRuntimeException;
import it.eng.spagobi.utilities.exceptions.SpagoBIServiceException;
import it.eng.spagobi.utilities.json.JSONUtils;
import it.eng.spagobi.utilities.rest.RestUtilities;

@Path("/qbequery")
@ManageAuthorization
public class QbeQueryResource extends AbstractQbeEngineResource {

	public static transient Logger logger = Logger.getLogger(QbeQueryResource.class);
	public static transient Logger auditlogger = Logger.getLogger("audit.query");
	private static final String PARAM_VALUE_NAME = "value";
	public static final String DEFAULT_VALUE_PARAM = "defaultValue";
	public static final String MULTI_PARAM = "multiValue";
	public static final String SERVICE_NAME = "SPAGOBI_SERVICE";
	public static final String DRIVERS = "DRIVERS";
	protected boolean handleTimeFilter = true;

	@POST
	@Path("/executeQuery")
	@Produces(MediaType.APPLICATION_JSON)
	public Response executeQuery(@javax.ws.rs.core.Context HttpServletRequest req, @QueryParam("start") String startS, @QueryParam("limit") String limitS,
			@QueryParam("currentQueryId") String id) {

		Integer limit = null;
		Integer start = null;
		Integer maxSize = null;
		IDataStore dataStore = null;
		Query query = null;

		Integer resultNumber = null;
		JSONObject gridDataFeed = new JSONObject();
		JSONObject jsonEncodedReq = null;
		JSONArray catalogue;
		Monitor totalTimeMonitor = null;
		Monitor errorHitsMonitor = null;
		JSONArray queries = null;
		JSONObject queryJSON = null;
		JSONArray subqueriesJSON = null;
		JSONObject subqueryJSON = null;
		logger.debug("IN");

		try {
			totalTimeMonitor = MonitorFactory.start("QbeEngine.executeQueryAction.totalTime");
			jsonEncodedReq = RestUtilities.readBodyAsJSONObject(req);

			JSONArray pars = jsonEncodedReq.optJSONArray(DataSetConstants.PARS);
			addParameters(pars);

			catalogue = jsonEncodedReq.getJSONArray("catalogue");
			if (catalogue == null) {
				catalogue = jsonEncodedReq.getJSONArray("qbeJSONQuery");
				JSONObject jo = new JSONObject(catalogue);
				jo = jo.getJSONObject("catalogue");
				queries = jo.getJSONArray("queries");
			} else {
				queries = new JSONArray(catalogue.toString());
			}

			logger.debug("catalogue" + " = [" + catalogue + "]");

			try {

				for (int i = 0; i < queries.length(); i++) {
					queryJSON = queries.getJSONObject(i);
					if (queryJSON.get("id").equals(id)) {
						query = deserializeQuery(queryJSON);
					} else {
						subqueriesJSON = queryJSON.getJSONArray("subqueries");
						for (int j = 0; j < subqueriesJSON.length(); j++) {
							subqueryJSON = subqueriesJSON.getJSONObject(j);
							if (subqueryJSON.get("id").equals(id)) {
								query = deserializeQuery(subqueryJSON);
							}
						}
					}
				}
			} catch (SerializationException e) {
				String message = "Impossible to deserialize query";
				throw new SpagoBIEngineServiceException("DESERIALIZATING QUERY", message, e);

			}

			SqlFilterModelAccessModality sqlModality = new SqlFilterModelAccessModality();
			UserProfile userProfile = (UserProfile) getEnv().get(EngineConstants.ENV_USER_PROFILE);

			logger.debug("Parameter [" + "limit" + "] is equals to [" + limit + "]");

			IModelAccessModality accessModality = getEngineInstance().getDataSource().getModelAccessModality();

			if (handleTimeFilter) {
				new TimeAggregationHandler(getEngineInstance().getDataSource()).handleTimeFilters(query);
			}

			Query filteredQuery = accessModality.getFilteredStatement(query, this.getEngineInstance().getDataSource(), userProfile.getUserAttributes());
			Map<IModelField, Set<IQueryField>> modelFieldsMap = filteredQuery.getQueryFields(getEngineInstance().getDataSource());
			Set<IModelField> modelFields = modelFieldsMap.keySet();
			Set<IModelEntity> modelEntities = Query.getQueryEntities(modelFields);

			modelEntities.addAll(sqlModality.getSqlFilterEntities(query, getEngineInstance().getDataSource()));
			// updateQueryGraphInQuery(filteredQuery, true, modelEntities);

			Map<String, Map<String, String>> inlineFilteredSelectFields = filteredQuery.getInlineFilteredSelectFields();

			boolean thereAreInlineTemporalFilters = inlineFilteredSelectFields != null && inlineFilteredSelectFields.size() > 0;
			if (thereAreInlineTemporalFilters) {
				limit = 0;
			}
			if (startS != null && !startS.equals("")) {
				start = Integer.parseInt(startS);
			}

			if (limitS != null && !limitS.equals("")) {
				limit = Integer.parseInt(limitS);
			}
			dataStore = executeQuery(start, limit, filteredQuery);
			if (thereAreInlineTemporalFilters) {
				dataStore = new TimeAggregationHandler(getEngineInstance().getDataSource()).handleTimeAggregations(filteredQuery, dataStore);
			}
			resultNumber = (Integer) dataStore.getMetaData().getProperty("resultNumber");

			logger.debug("Total records: " + resultNumber);
			boolean overflow = maxSize != null && resultNumber >= maxSize;
			if (overflow) {
				logger.warn("Query results number [" + resultNumber + "] exceeds max result limit that is [" + maxSize + "]");
				// auditlogger.info("[" + userProfile.getUserId() +
				// "]:: max result limit [" + maxSize + "] exceeded with SQL: "
				// + sqlQuery);
			}
			gridDataFeed = serializeDataStore(dataStore);
			return Response.ok(gridDataFeed.toString()).build();
		} catch (Throwable t) {
			errorHitsMonitor = MonitorFactory.start("QbeEngine.errorHits");
			errorHitsMonitor.stop();
			throw new SpagoBIServiceException(this.request.getPathInfo(), t.getMessage(), t);
		} finally {
			if (totalTimeMonitor != null)
				totalTimeMonitor.stop();
			logger.debug("OUT");
		}

	}

	private void addParameters(JSONArray parsListJSON) {
		try {
			if (parsListJSON != null) {

				for (int i = 0; i < parsListJSON.length(); i++) {
					JSONObject obj = (JSONObject) parsListJSON.get(i);
					String name = obj.getString("name");
					String type = null;
					if (obj.has("type")) {
						type = obj.getString("type");
					}

					// check if has value, if has not a valid value then use default
					// value
					boolean hasVal = obj.has(PARAM_VALUE_NAME) && !obj.getString(PARAM_VALUE_NAME).isEmpty();
					String tempVal = "";
					if (hasVal) {
						tempVal = obj.getString(PARAM_VALUE_NAME);
					} else {
						boolean hasDefaultValue = obj.has(DEFAULT_VALUE_PARAM);
						if (hasDefaultValue) {
							tempVal = obj.getString(DEFAULT_VALUE_PARAM);
							logger.debug("Value of param not present, use default value: " + tempVal);
						}
					}

					boolean multivalue = false;
					if (tempVal != null && tempVal.contains(",")) {
						multivalue = true;
					}

					String value = "";
					if (multivalue) {
						value = getMultiValue(tempVal, type);
					} else {
						value = getSingleValue(tempVal, type);
					}

					logger.debug("name: " + name + " / value: " + value);
					getEnv().put(name, value);
				}
			}

		} catch (Throwable t) {
			if (t instanceof SpagoBIServiceException) {
				throw (SpagoBIServiceException) t;
			}
			throw new SpagoBIServiceException(SERVICE_NAME, "An unexpected error occured while deserializing dataset parameters", t);
		}
	}

	private String getMultiValue(String value, String type) {
		String toReturn = "";

		String[] tempArrayValues = value.split(",");
		for (int j = 0; j < tempArrayValues.length; j++) {
			String tempValue = tempArrayValues[j];
			if (j == 0) {
				toReturn = getSingleValue(tempValue, type);
			} else {
				toReturn = toReturn + ", " + getSingleValue(tempValue, type);
			}
		}

		return toReturn;
	}

	private String getSingleValue(String value, String type) {
		String toReturn = "";
		value = value.trim();
		if (type.equalsIgnoreCase(DataSetUtilities.STRING_TYPE)) {
			if (!(value.startsWith("'") && value.endsWith("'"))) {
				toReturn = value;
			} else {
				toReturn = value;
			}
		} else if (type.equalsIgnoreCase(DataSetUtilities.NUMBER_TYPE)) {

			if (value.startsWith("'") && value.endsWith("'") && value.length() >= 2) {
				toReturn = value.substring(1, value.length() - 1);
			} else {
				toReturn = value;
			}
			if (toReturn == null || toReturn.length() == 0) {
				toReturn = "";
			}
		} else if (type.equalsIgnoreCase(DataSetUtilities.GENERIC_TYPE)) {
			toReturn = value;
		} else if (type.equalsIgnoreCase(DataSetUtilities.RAW_TYPE)) {
			if (value.startsWith("'") && value.endsWith("'") && value.length() >= 2) {
				toReturn = value.substring(1, value.length() - 1);
			} else {
				toReturn = value;
			}
		}

		return toReturn;
	}

	private void updateQueryGraphInQuery(Query filteredQuery, boolean b, Set<IModelEntity> modelEntities) {
		boolean isTheOldQueryGraphValid = false;
		logger.debug("IN");
		QueryGraph queryGraph = null;
		try {

			// calculate the default cover graph
			logger.debug("Calculating the default graph");
			IModelStructure modelStructure = getEngineInstance().getDataSource().getModelStructure();
			RootEntitiesGraph rootEntitiesGraph = modelStructure.getRootEntitiesGraph(getEngineInstance().getDataSource().getConfiguration().getModelName(),
					false);
			Graph<IModelEntity, Relationship> graph = rootEntitiesGraph.getRootEntitiesGraph();
			logger.debug("UndirectedGraph retrieved");
			Set<IModelEntity> entities = filteredQuery.getQueryEntities(getEngineInstance().getDataSource());
			if (entities.size() > 0) {
				entities.addAll(modelEntities);
				queryGraph = GraphManager.getDefaultCoverGraphInstance(QbeEngineConfig.getInstance().getDefaultCoverImpl()).getCoverGraph(graph, entities);
			}

			filteredQuery.setQueryGraph(queryGraph);

		} catch (Throwable t) {
			throw new SpagoBIRuntimeException("Error while loading the not ambigous graph", t);
		} finally {
			logger.debug("OUT");
		}

	}

	public JSONObject serializeDataStore(IDataStore dataStore) {
		JSONDataWriter dataSetWriter = new JSONDataWriter();
		JSONObject gridDataFeed = (JSONObject) dataSetWriter.write(dataStore);
		return gridDataFeed;
	}

	public static void updatePromptableFiltersValue(Query query, String promptableFilters) throws JSONException {
		updatePromptableFiltersValue(query, false, promptableFilters);
	}

	private Query deserializeQuery(JSONObject queryJSON) throws SerializationException, JSONException {
		// queryJSON.put("expression", queryJSON.get("filterExpression"));
		return SerializerFactory.getDeserializer("application/json").deserializeQuery(queryJSON.toString(), getEngineInstance().getDataSource());
	}

	public static void updatePromptableFiltersValue(Query query, boolean useDefault, String promptableFilters) throws JSONException {
		logger.debug("IN");
		List whereFields = query.getWhereFields();
		Iterator whereFieldsIt = whereFields.iterator();
		String[] question = { "?" };

		JSONObject requestPromptableFilters = new JSONObject(promptableFilters);

		while (whereFieldsIt.hasNext()) {
			WhereField whereField = (WhereField) whereFieldsIt.next();
			if (whereField.isPromptable()) {
				// getting filter value on request
				if (!useDefault || requestPromptableFilters != null) {
					JSONArray promptValuesList = requestPromptableFilters.optJSONArray(whereField.getName());
					if (promptValuesList != null) {
						String[] promptValues = toStringArray(promptValuesList);
						logger.debug("Read prompts " + promptValues + " for promptable filter " + whereField.getName() + ".");
						whereField.getRightOperand().lastValues = promptValues;
					}
				} else {
					whereField.getRightOperand().lastValues = question;
				}
			}
		}
		List havingFields = query.getHavingFields();
		Iterator havingFieldsIt = havingFields.iterator();
		while (havingFieldsIt.hasNext()) {
			HavingField havingField = (HavingField) havingFieldsIt.next();
			if (havingField.isPromptable()) {
				if (!useDefault || requestPromptableFilters != null) {
					// getting filter value on request
					// promptValuesList =
					// action.getAttributeAsList(havingField.getEscapedName());
					JSONArray promptValuesList = requestPromptableFilters.optJSONArray(havingField.getName());
					if (promptValuesList != null) {
						String[] promptValues = toStringArray(promptValuesList);
						logger.debug("Read prompt value " + promptValues + " for promptable filter " + havingField.getName() + ".");
						havingField.getRightOperand().lastValues = promptValues; // TODO
																					// how
																					// to
																					// manage
																					// multi-values
																					// prompts?
					}
				}
			} else {
				havingField.getRightOperand().lastValues = question;
			}
		}
		logger.debug("OUT");
	}

	private static String[] toStringArray(JSONArray o) throws JSONException {
		String[] promptValues = new String[o.length()];
		for (int i = 0; i < o.length(); i++) {
			promptValues[i] = o.getString(i);
		}
		return promptValues;
	}

	public IDataStore executeQuery(Integer start, Integer limit, Query q) {
		IDataStore dataStore = null;
		IDataSet dataSet = getActiveQueryAsDataSet(q);
		AbstractQbeDataSet qbeDataSet = (AbstractQbeDataSet) dataSet;
		IStatement statement = qbeDataSet.getStatement();

		Map<String, Object> envs = getEnv();
		String stringDrivers = envs.get(DRIVERS).toString();
		Map<String, Object> drivers = null;
		try {
			drivers = JSONObjectDeserializator.getHashMapFromString(stringDrivers);
		} catch (Exception e) {
			e.printStackTrace();
		}
		dataSet.setDrivers(drivers);

		QueryGraph graph = statement.getQuery().getQueryGraph();
		boolean valid = GraphManager.getGraphValidatorInstance(QbeEngineConfig.getInstance().getGraphValidatorImpl()).isValid(graph,
				statement.getQuery().getQueryEntities(getEngineInstance().getDataSource()));
		logger.debug("QueryGraph valid = " + valid);
		if (!valid) {
			throw new SpagoBIEngineServiceException("RELATIONS", "error.mesage.description.relationship.not.enough");
		}
		try {
			logger.debug("Executing query ...");
			Integer maxSize = QbeEngineConfig.getInstance().getResultLimit();
			logger.debug("Configuration setting  [" + "QBE.QBE-SQL-RESULT-LIMIT.value" + "] is equals to [" + (maxSize != null ? maxSize : "none") + "]");
			String jpaQueryStr = statement.getQueryString();

			logger.debug("Executable query (HQL/JPQL): [" + jpaQueryStr + "]");

			logQueryInAudit(qbeDataSet);

			dataSet.loadData(start, limit, (maxSize == null ? -1 : maxSize.intValue()));
			dataStore = dataSet.getDataStore();
			Assert.assertNotNull(dataStore, "The dataStore returned by loadData method of the class [" + dataSet.getClass().getName() + "] cannot be null");
		} catch (Exception e) {
			logger.debug("Query execution aborted because of an internal exceptian");
			SpagoBIEngineServiceException exception;
			String message;

			message = "An error occurred in  service while executing query: [" + statement.getQueryString() + "]";
			exception = new SpagoBIEngineServiceException("Execute query", message, e);
			exception.addHint("Check if the query is properly formed: [" + statement.getQueryString() + "]");
			exception.addHint("Check connection configuration");
			exception.addHint("Check the qbe jar file");

			throw exception;
		}
		logger.debug("Query executed succesfully");
		return dataStore;
	}

	private IDataSet getActiveQueryAsDataSet(Query q) {
		IStatement statement = getEngineInstance().getDataSource().createStatement(q);
		IDataSet dataSet;
		try {

			dataSet = QbeDatasetFactory.createDataSet(statement);
			boolean isMaxResultsLimitBlocking = QbeEngineConfig.getInstance().isMaxResultLimitBlocking();
			dataSet.setAbortOnOverflow(isMaxResultsLimitBlocking);

			Map userAttributes = new HashMap();
			UserProfile userProfile = (UserProfile) this.getEnv().get(EngineConstants.ENV_USER_PROFILE);
			userAttributes.putAll(userProfile.getUserAttributes());
			userAttributes.put(SsoServiceInterface.USER_ID, userProfile.getUserId().toString());

			dataSet.addBinding("attributes", userAttributes);
			dataSet.addBinding("parameters", this.getEnv());
			dataSet.setUserProfileAttributes(userAttributes);

			dataSet.setParamsMap(this.getEnv());

		} catch (Exception e) {
			logger.debug("Error getting the data set from the query");
			throw new SpagoBIRuntimeException("Error getting the data set from the query", e);
		}
		logger.debug("Dataset correctly taken from the query ");
		return dataSet;

	}

	private void logQueryInAudit(AbstractQbeDataSet dataset) {
		UserProfile userProfile = (UserProfile) getEnv().get(EngineConstants.ENV_USER_PROFILE);

		if (dataset instanceof JPQLDataSet) {
			auditlogger.info("[" + userProfile.getUserId() + "]:: JPQL: " + dataset.getStatement().getQueryString());
			auditlogger.info("[" + userProfile.getUserId() + "]:: SQL: " + ((JPQLDataSet) dataset).getSQLQuery(true));
		} else if (dataset instanceof HQLDataSet) {
			auditlogger.info("[" + userProfile.getUserId() + "]:: HQL: " + dataset.getStatement().getQueryString());
			auditlogger.info("[" + userProfile.getUserId() + "]:: SQL: " + ((HQLDataSet) dataset).getSQLQuery(true));
		} else {
			auditlogger.info("[" + userProfile.getUserId() + "]:: SQL: " + dataset.getStatement().getSqlQueryString());
		}

	}

	@POST
	@Path("/saveDataSet")
	@Produces(MediaType.APPLICATION_JSON)
	public Response saveDataSet(@javax.ws.rs.core.Context HttpServletRequest req) {
		Monitor totalTimeMonitor = null;
		Monitor errorHitsMonitor = null;
		Query query = null;
		JSONArray catalogue = null;
		JSONArray queries = null;
		JSONObject queryJSON = null;
		JSONArray subqueriesJSON = null;
		JSONObject subqueryJSON = null;
		try {
			handleTimeFilter = false;

			JSONObject jsonEncodedRequest = RestUtilities.readBodyAsJSONObject(req);
			catalogue = jsonEncodedRequest.getJSONArray("catalogue");
			if (catalogue == null) {
				catalogue = jsonEncodedRequest.getJSONArray("qbeJSONQuery");
				JSONObject jo = new JSONObject(catalogue);
				jo = jo.getJSONObject("catalogue");
				queries = jo.getJSONArray("queries");
			} else {
				queries = new JSONArray(catalogue.toString());
			}

			logger.debug("catalogue" + " = [" + catalogue + "]");

			try {

				for (int i = 0; i < queries.length(); i++) {
					queryJSON = queries.getJSONObject(i);
					if (queryJSON.get("id").equals(jsonEncodedRequest.getString("currentQueryId"))) {
						query = deserializeQuery(queryJSON);
					} else {
						subqueriesJSON = queryJSON.getJSONArray("subqueries");
						for (int j = 0; j < subqueriesJSON.length(); j++) {
							subqueryJSON = subqueriesJSON.getJSONObject(j);
							if (subqueryJSON.get("id").equals(jsonEncodedRequest.getString("currentQueryId"))) {
								query = deserializeQuery(subqueryJSON);
							}
						}
					}
				}
			} catch (SerializationException e) {
				throw new SpagoBIEngineServiceException(this.request.getPathInfo(), e.getMessage(), e);
			}
			String label = jsonEncodedRequest.getString("label");
			String schedulingCronLine = jsonEncodedRequest.getString("schedulingCronLine");
			String meta = jsonEncodedRequest.getString("meta");
			String qbeJSONQuery = jsonEncodedRequest.getString("qbeJSONQuery");
			String pars = getDataSetParametersAsString(jsonEncodedRequest);
			validateLabel(label);
			IDataSet dataset = getActiveQueryAsDataSet(query);
			int datasetId = -1;

			datasetId = saveQbeDataset(dataset, label, jsonEncodedRequest, schedulingCronLine, meta, qbeJSONQuery, pars);

			JSONObject obj = new JSONObject();
			obj.put("success", "true");
			obj.put("id", String.valueOf(datasetId));
			return Response.ok(obj.toString()).build();

		} catch (Throwable t) {
			errorHitsMonitor = MonitorFactory.start("QbeEngine.errorHits");
			errorHitsMonitor.stop();
			throw new SpagoBIServiceException(this.request.getPathInfo(), t.getMessage(), t);
		} finally {
			if (totalTimeMonitor != null)
				totalTimeMonitor.stop();
			logger.debug("OUT");
		}
	}

	private void validateLabel(String label) {
		DataSetServiceProxy proxy = (DataSetServiceProxy) getEnv().get(EngineConstants.ENV_DATASET_PROXY);
		IDataSet dataset = proxy.getDataSetByLabel(label);
		if (dataset != null) {
			throw new SpagoBIRuntimeException("Label already in use");
		}
	}

	private int saveQbeDataset(IDataSet dataset, String label, JSONObject jsonEncodedRequest, String schedulingCronLine, String meta, String qbeJSONQuery,
			String pars) throws JSONException {

		QbeDataSet newDataset = createNewQbeDataset(dataset, label, jsonEncodedRequest, schedulingCronLine, meta, qbeJSONQuery, pars);

		IDataSet datasetSaved = saveNewDataset(newDataset);

		int datasetId = datasetSaved.getId();
		return datasetId;
	}

	private QbeDataSet createNewQbeDataset(IDataSet dataset, String label, JSONObject jsonEncodedRequest, String schedulingCronLine, String meta,
			String qbeJSONQuery, String pars) throws JSONException {
		AbstractQbeDataSet qbeDataset = (AbstractQbeDataSet) dataset;

		QbeDataSet newDataset;

		UserProfile profile = (UserProfile) this.getEnv().get(EngineConstants.ENV_USER_PROFILE);

		// if its a federated dataset we've to add the dependent datasets
		if (getEnv().get(EngineConstants.ENV_FEDERATION) != null) {

			FederationDefinition federation = (FederationDefinition) getEnv().get(EngineConstants.ENV_FEDERATION);
			// Object relations = (getEnv().get(EngineConstants.ENV_RELATIONS));
			// if (relations != null) {
			// federation.setRelationships(relations.toString());
			// } else {
			// logger.debug("No relation defined " + relations);
			// }
			//
			// federation.setLabel((getEnv().get(EngineConstants.ENV_FEDERATED_ID).toString()));
			// federation.setFederation_id(new Integer((String)
			// (getEnv().get(EngineConstants.ENV_FEDERATED_ID))));

			newDataset = new FederatedDataSet(federation, (String) profile.getUserId());
			// ((FederatedDataSet)
			// newDataset).setDependentDataSets(federation.getSourceDatasets());
			newDataset.setDataSourceForWriting((IDataSource) getEnv().get(EngineConstants.ENV_DATASOURCE));
			newDataset.setDataSourceForReading((IDataSource) getEnv().get(EngineConstants.ENV_DATASOURCE));
		} else {
			newDataset = new QbeDataSet();
		}

		String name = jsonEncodedRequest.getString("name");
		String description = jsonEncodedRequest.getString("description");
		String scopeIdParam = jsonEncodedRequest.getString("scopeId");
		String scopeCdParam = jsonEncodedRequest.getString("scopeCd");
		String categoryIdParam = jsonEncodedRequest.optString("categoryId");
		String categoryCdParam = jsonEncodedRequest.optString("categoryCd");
		String isPersistedParam = jsonEncodedRequest.getString("isPersisted");
		String isScheduledParam = jsonEncodedRequest.getString("isScheduled");
		String persistTable = jsonEncodedRequest.getString("persistTable");
		String startDateField = jsonEncodedRequest.getString("startDateField");
		String endDateField = jsonEncodedRequest.getString("endDateField");
		newDataset.setLabel(label);
		newDataset.setName(name);
		newDataset.setDescription(description);
		if (pars != null) {
			newDataset.setParameters(pars);
		}
		String scopeCd = null;
		Integer scopeId = null;
		String categoryCd = null;
		Integer categoryId = null;

		if (jsonEncodedRequest.getString("scopeId") != null) {
			scopeCd = jsonEncodedRequest.getString("scopeCd");
			scopeId = Integer.parseInt(jsonEncodedRequest.getString("scopeId"));
		} else {
			scopeCd = SpagoBIConstants.DS_SCOPE_USER;
		}

		if (jsonEncodedRequest.opt("categoryId") != null) {
			categoryCd = jsonEncodedRequest.getString("categoryCd");
			categoryId = Integer.parseInt(jsonEncodedRequest.getString("categoryId"));
		} else {
			categoryCd = dataset.getCategoryCd();
			categoryId = dataset.getCategoryId();
		}
		if (categoryId == null
				&& (scopeCd.equalsIgnoreCase(SpagoBIConstants.DS_SCOPE_TECHNICAL) || scopeCd.equalsIgnoreCase(SpagoBIConstants.DS_SCOPE_ENTERPRISE))) {
			throw new SpagoBIRuntimeException("Dataset Enterprise or Technical must have a category");

		}
		newDataset.setScopeCd(scopeCd);
		newDataset.setScopeId(scopeId);
		newDataset.setCategoryCd(categoryCd);
		newDataset.setCategoryId(categoryId);

		String owner = profile.getUserId().toString();
		// saves owner of the dataset
		newDataset.setOwner(owner);

		String metadata = getMetadataAsString(dataset);
		logger.debug("Dataset's metadata: [" + metadata + "]");
		newDataset.setDsMetadata(metadata);

		newDataset.setDataSource(qbeDataset.getDataSource());

		String datamart = qbeDataset.getStatement().getDataSource().getConfiguration().getModelName();
		String datasource = qbeDataset.getDataSource().getLabel();
		JSONObject jsonConfig = new JSONObject();
		try {
			jsonConfig.put(QbeDataSet.QBE_DATA_SOURCE, datasource);
			jsonConfig.put(QbeDataSet.QBE_DATAMARTS, datamart);
			jsonConfig.put(QbeDataSet.QBE_JSON_QUERY, qbeJSONQuery);
			jsonConfig.put(FederatedDataSet.QBE_DATASET_CACHE_MAP, getEnv().get(EngineConstants.ENV_DATASET_CACHE_MAP));
		} catch (JSONException e) {
			throw new SpagoBIRuntimeException("Error while creating dataset's JSON config", e);
		}

		newDataset.setConfiguration(jsonConfig.toString());

		// get Persist and scheduling informations
		boolean isPersisted = Boolean.parseBoolean(isPersistedParam);
		newDataset.setPersisted(isPersisted);
		boolean isScheduled = Boolean.parseBoolean(isScheduledParam);
		newDataset.setScheduled(isScheduled);
		if (persistTable != null) {
			newDataset.setPersistTableName(persistTable);
		}
		if (startDateField != null) {
			newDataset.setStartDateField(startDateField);
		}
		if (endDateField != null) {
			newDataset.setEndDateField(endDateField);
		}
		if (schedulingCronLine != null) {
			newDataset.setSchedulingCronLine(schedulingCronLine);
		}

		try {

			JSONArray metadataArray = JSONUtils.toJSONArray(meta);

			IMetaData metaData = dataset.getMetadata();
			for (int i = 0; i < metaData.getFieldCount(); i++) {
				IFieldMetaData ifmd = metaData.getFieldMeta(i);
				for (int j = 0; j < metadataArray.length(); j++) {

					String fieldAlias = ifmd.getAlias() != null ? ifmd.getAlias() : "";
					// remove dataset source
					String fieldName = ifmd.getName().substring(ifmd.getName().indexOf(':') + 1);

					if (fieldAlias.equals((metadataArray.getJSONObject(j)).getString("name"))
							|| fieldName.equals((metadataArray.getJSONObject(j)).getString("name"))) {
						if ("MEASURE".equals((metadataArray.getJSONObject(j)).getString("fieldType"))) {
							ifmd.setFieldType(IFieldMetaData.FieldType.MEASURE);
						} else {
							ifmd.setFieldType(IFieldMetaData.FieldType.ATTRIBUTE);
						}
						break;
					}
				}
			}

			DatasetMetadataParser dsp = new DatasetMetadataParser();
			String dsMetadata = dsp.metadataToXML(metaData);

			newDataset.setDsMetadata(dsMetadata);

		} catch (Exception e) {
			logger.error("Error in calculating metadata");
			throw new SpagoBIRuntimeException("Error in calculating metadata", e);
		}

		return newDataset;
	}

	private String getMetadataAsString(IDataSet dataset) {
		IMetaData metadata = getDataSetMetadata(dataset);
		DatasetMetadataParser parser = new DatasetMetadataParser();
		String toReturn = parser.metadataToXML(metadata);
		return toReturn;
	}

	private IMetaData getDataSetMetadata(IDataSet dataset) {
		IMetaData metaData = null;
		Integer start = new Integer(0);
		Integer limit = new Integer(10);
		Integer maxSize = QbeEngineConfig.getInstance().getResultLimit();
		try {
			dataset.loadData(start, limit, maxSize);
			IDataStore dataStore = dataset.getDataStore();
			metaData = dataStore.getMetaData();
		} catch (Exception e) {
			throw new SpagoBIRuntimeException("Error while executing dataset", e);
		}
		return metaData;
	}

	private IDataSet saveNewDataset(IDataSet newDataset) {
		DataSetServiceProxy proxy = (DataSetServiceProxy) getEnv().get(EngineConstants.ENV_DATASET_PROXY);
		logger.debug("Saving new dataset ...");
		IDataSet saved = proxy.saveDataSet(newDataset);
		logger.debug("Dataset saved without errors");
		return saved;
	}

	private String getDataSetParametersAsString(JSONObject json) {
		String parametersString = null;

		try {
			JSONArray parsListJSON = json.optJSONArray(DataSetConstants.PARS);
			if (parsListJSON == null) {
				return null;
			}

			SourceBean sb = new SourceBean("PARAMETERSLIST");
			SourceBean sb1 = new SourceBean("ROWS");

			for (int i = 0; i < parsListJSON.length(); i++) {
				JSONObject obj = (JSONObject) parsListJSON.get(i);
				String name = obj.optString("name");
				String type = obj.optString("type");
				String multiValue = obj.optString("multiValue");
				String defaultValue = obj.optString("defaultValue");

				SourceBean b = new SourceBean("ROW");
				b.setAttribute("NAME", name);
				b.setAttribute("TYPE", type);
				b.setAttribute("MULTIVALUE", multiValue);
				b.setAttribute(DataSetParametersList.DEFAULT_VALUE_XML, defaultValue);
				sb1.setAttribute(b);
			}
			sb.setAttribute(sb1);
			parametersString = sb.toXML(false);
		} catch (Throwable t) {
			if (t instanceof SpagoBIServiceException) {
				throw (SpagoBIServiceException) t;
			}
			throw new SpagoBIServiceException("ManageDatasets", "An unexpected error occured while deserializing dataset parameters", t);

		}
		return parametersString;
	}

	@GET
	@Path("/domainCategories")
	@Produces(MediaType.APPLICATION_JSON)
	public String getCategoriesDomain() {
		logger.debug("IN");
		String userId = (String) getUserProfile().getUserUniqueIdentifier();
		QbeExecutionClient qbeExecutionClient;
		String categoryDomains = null;
		try {
			qbeExecutionClient = new QbeExecutionClient();
			categoryDomains = qbeExecutionClient.geCategoryDomain(userId);
		} catch (Throwable t) {
			logger.error("An unexpected error occured while executing service: QbeQueryResource.getDomainCategories", t);
			throw new SpagoBIServiceException(this.request.getPathInfo(),
					"An unexpected error occured while executing service: JsonChartTemplateService.getDomainCategories", t);
		} finally {
			logger.debug("OUT");
		}
		return categoryDomains;

	}

	@GET
	@Path("/domainScope")
	@Produces(MediaType.APPLICATION_JSON)
	public String getScopessDomain() {
		logger.debug("IN");
		String userId = (String) getUserProfile().getUserUniqueIdentifier();
		QbeExecutionClient qbeExecutionClient;
		String scopeDomains = null;
		try {
			qbeExecutionClient = new QbeExecutionClient();
			scopeDomains = qbeExecutionClient.geScopeDomain(userId);
		} catch (Throwable t) {
			logger.error("An unexpected error occured while executing service: QbeQueryResource.getDomainScopes", t);
			throw new SpagoBIServiceException(this.request.getPathInfo(),
					"An unexpected error occured while executing service: JsonChartTemplateService.getDomainScopes", t);
		} finally {
			logger.debug("OUT");
		}
		return scopeDomains;

	}

	@POST
	@Path("/export")
	@Produces(MediaType.TEXT_PLAIN)
	@UserConstraint(functionalities = { SpagoBIConstants.SELF_SERVICE_DATASET_MANAGEMENT })
	public Response export(@javax.ws.rs.core.Context HttpServletRequest req, @QueryParam("outputType") @DefaultValue("csv") String outputType,
			@QueryParam("currentQueryId") String id) {
		JSONObject jsonEncodedReq = null;
		JSONArray catalogue;
		Query query = null;
		JSONArray queries = null;
		JSONObject queryJSON = null;
		JSONArray subqueriesJSON = null;
		JSONObject subqueryJSON = null;

		try {
			jsonEncodedReq = RestUtilities.readBodyAsJSONObject(req);
			JSONArray pars = jsonEncodedReq.optJSONArray(DataSetConstants.PARS);
			catalogue = jsonEncodedReq.getJSONArray("catalogue");
			if (catalogue == null) {
				catalogue = jsonEncodedReq.getJSONArray("qbeJSONQuery");
				JSONObject jo = new JSONObject(catalogue);
				jo = jo.getJSONObject("catalogue");
				queries = jo.getJSONArray("queries");
			} else {
				queries = new JSONArray(catalogue.toString());
			}

			try {

				for (int i = 0; i < queries.length(); i++) {
					queryJSON = queries.getJSONObject(i);
					if (queryJSON.get("id").equals(id)) {
						query = deserializeQuery(queryJSON);
					} else {
						subqueriesJSON = queryJSON.getJSONArray("subqueries");
						for (int j = 0; j < subqueriesJSON.length(); j++) {
							subqueryJSON = subqueriesJSON.getJSONObject(j);
							if (subqueryJSON.get("id").equals(id)) {
								query = deserializeQuery(subqueryJSON);
							}
						}
					}
				}
			} catch (SerializationException e) {
				String message = "Impossible to deserialize query";
				throw new SpagoBIEngineServiceException("DESERIALIZATING QUERY", message, e);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

		UserProfile userProfile = (UserProfile) getEnv().get(EngineConstants.ENV_USER_PROFILE);
		IModelAccessModality accessModality = getEngineInstance().getDataSource().getModelAccessModality();
		Query filteredQuery = accessModality.getFilteredStatement(query, this.getEngineInstance().getDataSource(), userProfile.getUserAttributes());

		IDataSet dataSet = getActiveQueryAsDataSet(filteredQuery);
		dataSet.setUserProfileAttributes(getUserProfile().getUserAttributes());

		Assert.assertTrue(dataSet.isIterable(), "Impossible to export a non-iterable data set");
		DataIterator iterator = null;
		try {
			logger.debug("Starting iteration to transfer data");
			iterator = dataSet.iterator();

			StreamingOutput stream = new CsvStreamingOutput(iterator);

			ResponseBuilder response = Response.ok(stream);
			response.header("Content-Disposition", "attachment;filename=" + "report" + "." + outputType + "\";");
			return response.build();
		} catch (Exception e) {
			if (iterator != null) {
				iterator.close();
			}
			throw e;
		}
	}

}