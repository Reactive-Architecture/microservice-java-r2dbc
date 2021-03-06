package com.example.demor2dbc.kermoss.trx.services;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.stereotype.Component;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

import com.example.demor2dbc.kermoss.bfm.BaseTransactionCommand;
import com.example.demor2dbc.kermoss.bfm.LocalTransactionStepDefinition;
import com.example.demor2dbc.kermoss.bfm.TransactionsDAG;
import com.example.demor2dbc.kermoss.bfm.WorkerMeta;
import com.example.demor2dbc.kermoss.cache.BubbleCache;
import com.example.demor2dbc.kermoss.cache.BubbleMessage;
import com.example.demor2dbc.kermoss.entities.GlobalTransactionStatus;
import com.example.demor2dbc.kermoss.entities.LocalTransactionStatus;
import com.example.demor2dbc.kermoss.entities.WmGlobalTransaction;
import com.example.demor2dbc.kermoss.entities.WmLocalTransaction;
import com.example.demor2dbc.kermoss.events.BaseLocalTransactionEvent;
import com.example.demor2dbc.kermoss.events.BaseTransactionEvent;
import com.example.demor2dbc.kermoss.service.BusinessFlow;
import com.example.demor2dbc.kermoss.trx.message.CommitLtx;
import com.example.demor2dbc.kermoss.trx.message.StartLtx;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class LocalTransactionService {

	@Autowired
	private R2dbcEntityTemplate template;
	@Autowired
	private BusinessFlow businessFlow;

	@Autowired
	BubbleCache bubbleCache;
	@Autowired
	private ReactiveTransactionManager tm;

	@Autowired
	private TransactionsDAG dag;

	@EventListener
	public Mono<Void> begin(StartLtx event) {
		TransactionalOperator rxtx = TransactionalOperator.create(tm);
		LocalTransactionStepDefinition<BaseLocalTransactionEvent> pipeline = event.getPipeline();
		String transactionName = pipeline.getMeta().getTransactionName();

		Mono<BubbleMessage> bubbleMessage = bubbleCache.getBubble(pipeline.getIn().getId()).switchIfEmpty(
				Mono.error(() -> new RuntimeException("this event is not linked to any transaction context")));

		return bubbleMessage.flatMap(
				bm -> findGtx(bm, transactionName).flatMap(wgtx -> findLtxByNameAndGtxId(transactionName, wgtx.getId())
						.switchIfEmpty(newLocalTransaction(bm, pipeline.getMeta()))))
				.flatMap(wml -> {
					Mono<Void> mono = outerPipeToMono(wml, pipeline);
					if (wml.isNew()) {
						return template.insert(wml).then(innerPipeToMono(wml, pipeline)).then(mono);
					}
					return mono;
				}).thenEmpty(businessFlow.consumeSafeEvent(pipeline.getIn()).then()).as(rxtx::transactional);

	}

	//TODOx refactored
	Mono<WmLocalTransaction> newLocalTransaction(BubbleMessage bm, WorkerMeta meta) {
		WmLocalTransaction wml = new WmLocalTransaction();
		wml.setNew(true);
		wml.setId(UUID.randomUUID().toString());
		wml.setFltx(bm.getFLTX());
		wml.setGtxId(bm.getGLTX());
		wml.setName(meta.getTransactionName());
		// attach LTX to his parent if Exist
		return findLtxByNameAndGtxId(meta.getChildOf(), bm.getGLTX()).map(x -> {
			wml.setLtxId(x.getId());
			return wml;
		}).defaultIfEmpty(wml);
	}

	@EventListener
	public Mono<Void> commit(CommitLtx event) {
		TransactionalOperator rxtx = TransactionalOperator.create(tm);
		LocalTransactionStepDefinition<BaseLocalTransactionEvent> pipeline = event.getPipeline();
		String transactionName = pipeline.getMeta().getTransactionName();

		Mono<BubbleMessage> bubbleMessage = bubbleCache.getBubble(pipeline.getIn().getId()).switchIfEmpty(
				Mono.error(() -> new RuntimeException("this event is not linked to any transaction context")));

		return bubbleMessage
				.flatMap(bm -> findGtx(bm, transactionName)
						.flatMap(wgtx -> findLtxByNameAndGtxId(transactionName, wgtx.getId()).switchIfEmpty(Mono.error(
								new RuntimeException("no local transaction started with name " + transactionName)))))
				.flatMap(wml -> {
					Mono<Void> mono = outerPipeToMono(wml, pipeline);
					if (wml.getState().equals(LocalTransactionStatus.STARTED)) {
						wml.setState(LocalTransactionStatus.COMITTED);
						Mono<Void> updateLtx = template.update(wml).then(innerPipeToMono(wml, pipeline)).then(mono);
						return findNestedLtx(wml).all(el -> el.getState().equals(LocalTransactionStatus.COMITTED))
								.defaultIfEmpty(true).flatMap(res -> {
									if (res.equals(true)) {
										return updateLtx;
									} else {
										return Mono.empty();
									}
								});
					} else if (wml.getState().equals(LocalTransactionStatus.COMITTED)) {
						return mono;
					} else {
						return Mono.empty();
					}
				}).thenEmpty(businessFlow.consumeSafeEvent(pipeline.getIn()).then()).as(rxtx::transactional);

	}

	// TODOx: tobe refactored

	public Mono<WmGlobalTransaction> findGtx(BubbleMessage bm, String transactionName) {
		List<String> predecessors = dag.predecessors(transactionName);

		String globalTransactionName = predecessors.remove(predecessors.size() - 1);
		// remove the current transactionName
		predecessors.remove(0);

		Mono<WmGlobalTransaction> globalTransaction = Mono.empty();

		String GTX = bm.getGLTX();
		String PGTX = bm.getPGTX();
		if (GTX == null && PGTX != null) {
			globalTransaction = template.select(WmGlobalTransaction.class)
					.matching(Query.query(Criteria.where("parent").is(bm.getPGTX()).and("name")
							.is(globalTransactionName).and("status").is(GlobalTransactionStatus.STARTED)))
					.one().switchIfEmpty(Mono.error(() -> new RuntimeException("Cannot find a started GTX")));
		} else {
			globalTransaction = template.select(WmGlobalTransaction.class)
					.matching(Query.query(
							Criteria.where("id").is(bm.getGLTX()).and("status").is(GlobalTransactionStatus.STARTED)))
					.one().switchIfEmpty(Mono.error(() -> new RuntimeException("Cannot find a stared GTX")));
		}

		if (!predecessors.isEmpty()) {
			globalTransaction =globalTransaction.flatMap(gt->checkAllLtxParentsStarted(gt.getId(),predecessors).flatMap(b->{
				if(b.equals(true)) {
					return Mono.just(gt);
				}else {
					return Mono.error(new RuntimeException("Not all parents for the Local Transaction "+transactionName+" are started"));
				}
			}));
		}
		return globalTransaction;
	}

	Mono<Boolean> checkAllLtxParentsStarted(String gtx_id, List<String> predecessors) {
		return template.select(WmLocalTransaction.class).
		 matching(Query.query(Criteria.where("gtx_id").
				 is(gtx_id).and("state")
				.is(LocalTransactionStatus.STARTED).and("name").in(predecessors))).count().map(count -> {
					if (predecessors.size() == count) {
						return true;
					} else {
					   return false;
					}
				});
	}

	public Mono<WmLocalTransaction> findLtxByNameAndGtxId(String name, String gtxId) {
		return template.select(WmLocalTransaction.class)
				.matching(Query.query(Criteria.where("gtx_id").is(gtxId).and("name").is(name))).one();
	}

	public Flux<WmLocalTransaction> findNestedLtx(WmLocalTransaction wml) {
		return template.select(WmLocalTransaction.class)
				.matching(Query.query(Criteria.where("gtx_id").is(wml.getGtxId()).and("ltx_id").is(wml.getId()))).all();
	}

	Mono<Void> innerPipeToMono(WmLocalTransaction wml, LocalTransactionStepDefinition pipeline) {
		Mono<Void> mono = Mono.empty();
		// add Transaction context in buubleMessage
		Mono<BubbleMessage> bubbleMessage = BuildBubbleMessage(wml);
		Stream<BaseTransactionCommand> send = pipeline.getSend();
		if (send != null) {
			Mono<Void> sendFlux = Flux.fromStream(send).concatMap(c -> bubbleCache
					.getOrAddBubble(c.getId(), bubbleMessage).then(businessFlow.recieveOutBoundCommand(c))).then();
			mono = mono.then(sendFlux);
		}
		return mono;
	}

	// tu dois gerer le blow des events check is event exist before blowing events
	// ...
	// si une erreur survient il se peut que vous insérez le meme evenement lié au
	// meme gtx
	Mono<Void> outerPipeToMono(WmLocalTransaction wml, LocalTransactionStepDefinition pipeline) {
		Mono<Void> mono = Mono.empty();
		// add Transaction context in buubleMessage
		Mono<BubbleMessage> bubbleMessage = BuildBubbleMessage(wml);
		Stream<BaseTransactionEvent> blow = pipeline.getBlow();
		if (blow != null) {
			Mono<Void> blowFlux = Flux.fromStream(blow).concatMap(
					e -> bubbleCache.getOrAddBubble(e.getId(), bubbleMessage).then(businessFlow.publishSafeEvent(e)))
					.then();
			mono = mono.then(blowFlux);
		}
		return mono;
	}

	// voir une method to add pgtx;
	Mono<BubbleMessage> BuildBubbleMessage(WmLocalTransaction wml) {
		return Mono.just(BubbleMessage.builder().GLTX(wml.getGtxId()).LTX(wml.getId()).FLTX(wml.getFltx()).build());
	}

}