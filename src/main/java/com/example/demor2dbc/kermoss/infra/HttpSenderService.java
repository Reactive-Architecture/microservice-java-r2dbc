package com.example.demor2dbc.kermoss.infra;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.data.relational.core.query.Update;
import org.springframework.data.relational.core.sql.SqlIdentifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.reactive.function.client.WebClient;

import com.example.demor2dbc.kermoss.entities.WmOutboundCommand;

import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.Logger;
import reactor.util.Loggers;

@Component
public class HttpSenderService {
	public static final Logger LOG = Loggers.getLogger(HttpSenderService.class);
	
	@Autowired
	private ReactiveTransactionManager tm;

	@Autowired
	private OutBoundCommandHttpFlux outboundCommandHttpFlux;

	@Autowired
	private R2dbcEntityTemplate template;
	
	@PostConstruct
	public void init() {
		TransactionalOperator rxtx = TransactionalOperator.create(tm);   

		outboundCommandHttpFlux.flux().
		onBackpressureBuffer(255*3, x->{
			//making some side effect here 
			LOG.warn("Overflow: discarding element from queue="+x);
			}, 
				BufferOverflowStrategy.DROP_LATEST)
		.publishOn(Schedulers.boundedElastic())		
		.flatMap(tc -> send(tc)
						.flatMap(e->{
							WmOutboundCommand wmOutboundCommand = new WmOutboundCommand();
							if(e.is2xxSuccessful()) {
								wmOutboundCommand.changeStatusToDelivered();
							}else {
								wmOutboundCommand.changeStatusToFailed();
							}
							return template.update(Query.query(Criteria.where("id").is(tc.getRefId())),
									toPq(wmOutboundCommand),
									WmOutboundCommand.class); 
						}).as(rxtx::transactional)
						)
				
		.onErrorContinue((ex,object)->{
    		LOG.error("connot send event over HTTP layer "+object.toString(),ex);
    	})				
				.subscribe();

	}

	private Mono<HttpStatus> send(TransporterCommand tc) {
		return WebClient.builder().baseUrl("http://localhost:8080/command-executor").build().post().uri("/commands")
				.contentType(MediaType.APPLICATION_JSON).body(Mono.just(tc), TransporterCommand.class)
				.exchangeToMono(res -> Mono.just(res.statusCode()));
	}

	public Update toPq(WmOutboundCommand wmo) {
		Map<SqlIdentifier, Object> columnsToUpdate = new LinkedHashMap<SqlIdentifier, Object>();
		columnsToUpdate.put(SqlIdentifier.unquoted("status"), wmo.getStatus());
		if (wmo.getDeliveredTimestamp() != null) {
			columnsToUpdate.put(SqlIdentifier.unquoted("delivered_timestamp"), wmo.getDeliveredTimestamp());
		}
		if (wmo.getFailedTimestamp() != null) {
			columnsToUpdate.put(SqlIdentifier.unquoted("failed_timestamp"), wmo.getFailedTimestamp());
		}
		return Update.from(columnsToUpdate);

	}

}