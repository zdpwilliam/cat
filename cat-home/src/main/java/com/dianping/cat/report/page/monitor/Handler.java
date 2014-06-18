package com.dianping.cat.report.page.monitor;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

import javax.servlet.ServletException;

import org.unidal.lookup.annotation.Inject;
import org.unidal.web.mvc.PageHandler;
import org.unidal.web.mvc.annotation.InboundActionMeta;
import org.unidal.web.mvc.annotation.OutboundActionMeta;
import org.unidal.web.mvc.annotation.PayloadMeta;

import com.dianping.cat.Cat;
import com.dianping.cat.helper.TimeUtil;
import com.dianping.cat.message.Message;
import com.dianping.cat.message.Metric;
import com.dianping.cat.message.Transaction;
import com.dianping.cat.message.internal.DefaultMetric;
import com.dianping.cat.message.internal.DefaultTransaction;
import com.dianping.cat.message.spi.internal.DefaultMessageTree;
import com.dianping.cat.report.ReportPage;
import com.dianping.cat.report.page.JsonBuilder;
import com.dianping.cat.report.task.alert.MetricType;
import com.site.lookup.util.StringUtils;

public class Handler implements PageHandler<Context> {
	@Inject
	private JspViewer m_jspViewer;
	
	@Inject
	private JsonBuilder m_builder;
	
	public HttpStatus checkPars(Payload payload) {
		StringBuilder sb = new StringBuilder();
		String domain = payload.getDomain();
		String group = payload.getGroup();
		String key = payload.getKey();
		String action = payload.getAction().getName();
		HttpStatus httpStatus = new HttpStatus();
		boolean error = false;

		if (StringUtils.isEmpty(domain)) {
			sb.append("domain ");
			error = true;
		}
		if (StringUtils.isEmpty(group)) {
			sb.append("group ");
			error = true;
		}
		if (StringUtils.isEmpty(key) && !Action.BATCH_API.getName().equalsIgnoreCase(action)) {
			sb.append("key ");
			error = true;
		}
		if (error) {
			httpStatus.setErrorMsg("invalid field: " + sb.toString());
			httpStatus.setStatusCode(String.valueOf(HttpStatus.FAIL));
		} else {
			httpStatus.setStatusCode(String.valueOf(HttpStatus.SUCCESS));
		}

		return httpStatus;
	}

	@Override
	@PayloadMeta(Payload.class)
	@InboundActionMeta(name = "monitor")
	public void handleInbound(Context ctx) throws ServletException, IOException {
		// display only, no action here
	}

	@Override
	@OutboundActionMeta(name = "monitor")
	public void handleOutbound(Context ctx) throws ServletException, IOException {
		Model model = new Model(ctx);
		Payload payload = ctx.getPayload();
		Action action = payload.getAction();
		HttpStatus status = checkPars(payload);

		model.setStatus(m_builder.toJson(status));
		if (status.getStatusCode().equals(String.valueOf(HttpStatus.SUCCESS))) {
			String domain = payload.getDomain();
			String group = payload.getGroup();
			String key = payload.getKey();
			long time = payload.getTimestamp();
			long count = payload.getCount();
			boolean invalid = time < TimeUtil.getCurrentHour().getTime();

			if (invalid) {
				Cat.logError(new RuntimeException("Error timestamp in metric api, time"
				      + new SimpleDateFormat("yyyy-MM-dd HH:ss").format(new Date(time)) + payload.toString()));

				time = System.currentTimeMillis();
			}

			switch (action) {
			case COUNT_API:
				buildMetric(group, key, MetricType.COUNT.name(), time, count);
				break;
			case AVG_API:
				buildMetric(group, key, MetricType.AVG.name(), time, payload.getAvg());
				break;
			case SUM_API:
				buildMetric(group, key, MetricType.SUM.name(), time, payload.getSum());
				break;
			case BATCH_API:
				buildBatchMetric(group, payload.getBatch());
				break;
			default:
				throw new RuntimeException("Unknown action: " + action);
			}
			DefaultMessageTree tree = (DefaultMessageTree) Cat.getManager().getThreadLocalMessageTree();
			tree.setDomain(domain);

			Message message = tree.getMessage();
			if (message instanceof Transaction) {
				((DefaultTransaction) message).setTimestamp(time);
			}
		}
		model.setAction(action);
		model.setPage(ReportPage.MONITOR);
		m_jspViewer.view(ctx, model);
	}

	private Metric buildMetric(String group, String key, String type, long time, double value) {
		Metric metric = Cat.getProducer().newMetric(group, key);
		DefaultMetric defaultMetric = (DefaultMetric) metric;

		if (defaultMetric != null) {
			defaultMetric.setTimestamp(time);
			if (MetricType.SUM.name().equalsIgnoreCase(type)) {
				defaultMetric.setStatus("S,C");
				defaultMetric.addData(String.format("%s,%.2f", 1, value));
			} else if (MetricType.AVG.name().equalsIgnoreCase(type)) {
				defaultMetric.setStatus("T");
				defaultMetric.addData(String.format("%.2f", value));
			} else if (MetricType.AVG.name().equalsIgnoreCase(type)) {
				defaultMetric.setStatus("C");
				defaultMetric.addData(String.valueOf(value));
			}
		}
		return defaultMetric;
	}

	private boolean validateNumber(String longNumber, String doubleNumber) {
		try {
			if (StringUtils.isNotEmpty(longNumber) && StringUtils.isNotEmpty(doubleNumber)) {
				Long.parseLong(longNumber);
				Double.parseDouble(doubleNumber);
				return true;
			} else {
				return false;
			}

		} catch (Exception e) {
			return false;
		}
	}

	private void buildBatchMetric(String group, String content) {
		String[] lines = content.split("\n");

		for (String line : lines) {
			String[] tabs = line.split("\t");

			if (tabs.length == 4 & validateNumber(tabs[2], tabs[3])) {
				String key = tabs[0];
				String type = tabs[1];
				long time = Long.parseLong(tabs[2]);
				double value = Double.parseDouble(tabs[3]);

				buildMetric(group, key, type, time, value);
			} else {
				Cat.logError(new RuntimeException("Unrecognized batch data: " + line));
			}
		}
	}

}