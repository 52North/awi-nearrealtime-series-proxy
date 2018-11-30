package org.n52.sensorweb.awi;

import static java.util.stream.Collectors.toList;

import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.n52.janmayen.function.Functions;
import org.n52.proxy.config.DataSourceConfiguration;
import org.n52.proxy.connector.AbstractSosConnector;
import org.n52.proxy.connector.ConnectorRequestFailedException;
import org.n52.proxy.connector.constellations.QuantityDatasetConstellation;
import org.n52.proxy.connector.utils.DataEntityBuilder;
import org.n52.proxy.connector.utils.EntityBuilder;
import org.n52.proxy.connector.utils.ServiceConstellation;
import org.n52.proxy.db.beans.ProxyServiceEntity;
import org.n52.series.db.beans.CategoryEntity;
import org.n52.series.db.beans.DataEntity;
import org.n52.series.db.beans.DatasetEntity;
import org.n52.series.db.beans.FeatureEntity;
import org.n52.series.db.beans.OfferingEntity;
import org.n52.series.db.beans.PhenomenonEntity;
import org.n52.series.db.beans.ProcedureEntity;
import org.n52.series.db.beans.UnitEntity;
import org.n52.series.db.dao.DbQuery;
import org.n52.series.db.dao.JTSGeometryConverter;
import org.n52.shetland.ogc.filter.TemporalFilter;
import org.n52.shetland.ogc.gml.CodeType;
import org.n52.shetland.ogc.gml.time.TimePeriod;
import org.n52.shetland.ogc.om.ObservationStream;
import org.n52.shetland.ogc.om.ObservationValue;
import org.n52.shetland.ogc.om.OmObservation;
import org.n52.shetland.ogc.om.SingleObservationValue;
import org.n52.shetland.ogc.om.features.samplingFeatures.SamplingFeature;
import org.n52.shetland.ogc.ows.service.GetCapabilitiesResponse;
import org.n52.shetland.ogc.sos.Sos2Constants;
import org.n52.shetland.ogc.sos.SosCapabilities;
import org.n52.shetland.ogc.sos.SosConstants;
import org.n52.shetland.ogc.sos.SosObservationOffering;
import org.n52.shetland.ogc.sos.gda.GetDataAvailabilityResponse;
import org.n52.shetland.ogc.sos.gda.GetDataAvailabilityResponse.DataAvailability;
import org.n52.shetland.ogc.sos.request.GetObservationRequest;
import org.n52.shetland.ogc.sos.response.GetObservationResponse;
import org.n52.shetland.util.MinMax;

/**
 * TODO JavaDoc
 *
 * @author Christian Autermann
 */
public class AWISOSConnector extends AbstractSosConnector {
    private static final String SERVICE_DESCRIPTION = "AWI NearRealTime SOS";
    private static final Logger LOG = LoggerFactory.getLogger(AWISOSConnector.class);

    @Override
    protected boolean canHandle(DataSourceConfiguration config, GetCapabilitiesResponse capabilities) {
        return getClass().getName().equals(config.getConnector());
    }

    @Override
    public ServiceConstellation getConstellation(DataSourceConfiguration config, GetCapabilitiesResponse response) {

        config.setConnector(getConnectorName());

        ServiceConstellation service = new ServiceConstellation();
        service.setService(createServiceEntity(config));

        ((SosCapabilities) response.getCapabilities()).getContents()
                .map(Collection::stream)
                .orElseGet(Stream::empty)
                .filter(this::checkOffering)
                .forEach(offering -> addObservationOffering(service, offering));

        return service;
    }

    @Override
    public List<DataEntity<?>> getObservations(DatasetEntity dataset, DbQuery query) {
        return getObservation(dataset, createTimeFilter(query), createSpatialFilter(query))
                .getObservationCollection().toStream()
                .map(getDataCreator(dataset))
                .collect(toList());
    }

    @Override
    public UnitEntity getUom(DatasetEntity dataset) {
        return getLatest(dataset)
                .map(OmObservation::getValue)
                .map(ObservationValue::getValue)
                .flatMap(Functions.castIfInstanceOf(SingleObservationValue.class))
                .map(SingleObservationValue<?>::getUnit)
                .map(unit -> EntityBuilder.createUnit(unit, unit, (ProxyServiceEntity) dataset.getService()))
                .orElseGet(() -> EntityBuilder.createUnit("", "", (ProxyServiceEntity) dataset.getService()));
    }

    private Optional<TimePeriod> getTimeRange(DatasetEntity dataset) {
        return Optional.ofNullable(getDataAvailability(dataset))
                .map(GetDataAvailabilityResponse::getDataAvailabilities)
                .map(List::stream).orElseGet(Stream::empty)
                .map(DataAvailability::getPhenomenonTime)
                .findFirst();
    }

    @Override
    public Optional<DataEntity<?>> getFirstObservation(DatasetEntity dataset) {
        return getFirst(dataset).map(getDataCreator(dataset));
    }

    private Optional<OmObservation> getFirst(DatasetEntity dataset) {
        return getFirstLatest(dataset, getTimeRange(dataset).map(TimePeriod::getStart));
    }

    @Override
    public Optional<DataEntity<?>> getLastObservation(DatasetEntity dataset) {
        return getLatest(dataset).map(getDataCreator(dataset));
    }

    private Optional<OmObservation> getLatest(DatasetEntity dataset) {
        return getFirstLatest(dataset, getTimeRange(dataset).map(TimePeriod::getEnd));
    }

    private void addObservationOffering(ServiceConstellation service,
                                        SosObservationOffering observationOffering) {
        observationOffering.getProcedures().forEach(procedureId -> {
            try {
                getDataAvailabilityByProcedure(procedureId, service.getService().getUrl()).getDataAvailabilities()
                        .forEach(da -> {
                            createFeatureEntity(service, da);
                            createProcedureEntity(service, da, !observationOffering.getObservedArea().is1D());
                            createPhenomenonEntity(service, da);
                            createCategoryEntity(service, da);
                            createOfferingEntity(service, da);
                            QuantityDatasetConstellation dataset = createDatasetConstellation(da);
                            service.add(dataset);
                            try {
                                getFirstLatest(da, service).ifPresent(minmax -> {
                                    dataset.setFirst(minmax.getMinimum());
                                    dataset.setLatest(minmax.getMaximum());
                                });
                            } catch (ConnectorRequestFailedException ex) {
                                LOG.error("Failed to add dataset", ex);
                            }
                        });
            } catch (ConnectorRequestFailedException ex) {
                LOG.error("Failed to add dataset", ex);
            }
        });
    }

    private Optional<MinMax<? extends DataEntity<?>>> getFirstLatest(DataAvailability da, ServiceConstellation service) {
        return Optional.ofNullable(getFirstLatest(da, service.getService().getUrl()))
                .map(GetObservationResponse::getObservationCollection)
                .map(ObservationStream::toStream)
                .map(stream -> stream.map(DataEntityBuilder::createQuantityDataEntity)
                .map(x -> (DataEntity<?>) x)
                .sorted(Comparator.comparing(DataEntity::getTimestart, Comparator.naturalOrder()))
                .collect(toList()))
                .map(this::asMinMax);
    }

    private GetObservationResponse getFirstLatest(DataAvailability da, String serviceURL) {
        GetObservationRequest request = new GetObservationRequest(SosConstants.SOS, Sos2Constants.SERVICEVERSION);
        request.addProcedure(da.getProcedure().getHref());
        request.addOffering(da.getOffering().getHref());
        request.addObservedProperty(da.getObservedProperty().getHref());
        request.addFeatureIdentifier(da.getFeatureOfInterest().getHref());
        List<TemporalFilter> temporalFilters = Stream.of(da.getPhenomenonTime().getStart(),
                                                         da.getPhenomenonTime().getEnd())
                .map(this::createTimeFilter).collect(toList());
        request.setTemporalFilters(temporalFilters);
        return (GetObservationResponse) getSosResponseFor(request, Sos2Constants.NS_SOS_20, serviceURL);
    }

    private <T> MinMax<T> asMinMax(List<T> list) {
        if (list.size() < 1 || list.size() > 2) {
            return null;
        }
        Iterator<T> iterator = list.iterator();
        T minimum = iterator.next();
        T maximum = iterator.hasNext() ? iterator.next() : minimum;
        return new MinMax<>(minimum, maximum);
    }

    private Optional<OmObservation> getFirstLatest(DatasetEntity dataset, Optional<DateTime> time) {
        return time.map(this::createTimeFilter)
                .map(filter -> getObservation(dataset, filter))
                .map(GetObservationResponse::getObservationCollection)
                .map(ObservationStream::toStream)
                .flatMap(Stream::findFirst);
    }

    private boolean checkOffering(SosObservationOffering offering) {
        if (!offering.isSetObservableProperties()) {
            LOG.info("Skipping offering {} as it has no observable properties", offering.getIdentifier());
            return false;
        }
        if (!offering.isSetRelatedFeature()) {
            LOG.info("Skipping offering {} as it has no features of interest", offering.getIdentifier());
            return false;
        }
        if (!offering.isSetPhenomenonTime()) {
            LOG.info("Skipping offering {} as it has no phenomenon time", offering.getIdentifier());
            return false;
        }
        if (!offering.isSetObservedArea()) {
            LOG.info("Skipping offering {} as it has no observed area", offering.getIdentifier());
            return false;
        }
        return true;
    }

    private ProxyServiceEntity createServiceEntity(DataSourceConfiguration config) {
        ProxyServiceEntity service = new ProxyServiceEntity();
        service.setName(config.getItemName());
        service.setDescription(SERVICE_DESCRIPTION);
        service.setVersion(config.getVersion());
        service.setType(config.getType());
        service.setUrl(config.getUrl());
        service.setConnector(config.getConnector());
        return service;
    }

    private Function<OmObservation, DataEntity<?>> getDataCreator(DatasetEntity dataset) {
        return observation -> createDataEntity(observation, dataset);
    }

    private PhenomenonEntity createPhenomenonEntity(ServiceConstellation service, DataAvailability da) {
        return service.getPhenomena().computeIfAbsent(
                da.getObservedProperty().getHref(),
                phenomenonId -> {
                    PhenomenonEntity phenomenon = new PhenomenonEntity();
                    phenomenon.setName(da.getObservedProperty().getTitleOrFromHref());
                    phenomenon.setDomainId(phenomenonId);
                    return phenomenon;
                });
    }

    private CategoryEntity createCategoryEntity(ServiceConstellation service, DataAvailability da) {
        return service.getCategories().computeIfAbsent(
                da.getObservedProperty().getHref(),
                categoryId -> {
                    CategoryEntity category = new CategoryEntity();
                    category.setName(da.getObservedProperty().getTitleOrFromHref());
                    category.setDomainId(categoryId);
                    return category;
                });
    }

    private OfferingEntity createOfferingEntity(ServiceConstellation service, DataAvailability da) {
        return service.getOfferings().computeIfAbsent(
                da.getOffering().getHref(),
                offeringId -> {
                    OfferingEntity offering = new OfferingEntity();
                    offering.setName(da.getOffering().getTitleOrFromHref());
                    offering.setDomainId(offeringId);
                    offering.setPhenomenonTimeStart(da.getPhenomenonTime().getStart().toDate());
                    offering.setPhenomenonTimeEnd(da.getPhenomenonTime().getEnd().toDate());
                    offering.setResultTimeStart(da.getPhenomenonTime().getStart().toDate());
                    offering.setResultTimeStart(da.getPhenomenonTime().getEnd().toDate());
                    return offering;
                });
    }

    private ProcedureEntity createProcedureEntity(ServiceConstellation service, DataAvailability da, boolean mobile) {
        return service.getProcedures().computeIfAbsent(
                da.getProcedure().getHref(),
                procedureId -> {
                    ProcedureEntity procedure = new ProcedureEntity();
                    procedure.setMobile(mobile);
                    procedure.setInsitu(true);
                    procedure.setName(da.getProcedure().getTitleOrFromHref());
                    procedure.setDomainId(procedureId);
                    return procedure;
                });

    }

    private FeatureEntity createFeatureEntity(ServiceConstellation service, DataAvailability da) {
        return service.getFeatures().computeIfAbsent(
                da.getFeatureOfInterest().getHref(),
                featureId -> {
                    SamplingFeature f = (SamplingFeature) getFeatureOfInterestById(
                            featureId, service.getService().getUrl()).getAbstractFeature();
                    FeatureEntity feature = new FeatureEntity();
                    feature.setGeometry(JTSGeometryConverter.convert(f.getGeometry()));
                    feature.setDomainId(f.getIdentifier());
                    feature.setName(Optional.ofNullable(f.getFirstName())
                            .map(CodeType::getValue)
                            .orElseGet(feature::getDomainId));
                    feature.setDescription(f.getDescription());
                    return feature;

                });

    }

    private QuantityDatasetConstellation createDatasetConstellation(DataAvailability da) {
        return new QuantityDatasetConstellation(
                da.getProcedure().getHref(),
                da.getOffering().getHref(),
                da.getObservedProperty().getHref(),
                da.getObservedProperty().getHref(),
                da.getFeatureOfInterest().getHref());
    }

}
