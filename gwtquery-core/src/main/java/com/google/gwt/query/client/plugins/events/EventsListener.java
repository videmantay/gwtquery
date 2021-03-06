/*
 * Copyright 2011, The gwtquery team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.gwt.query.client.plugins.events;

import static com.google.gwt.query.client.GQuery.$;

import com.google.gwt.core.client.Duration;
import com.google.gwt.core.client.JsArrayString;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.EventTarget;
import com.google.gwt.dom.client.NodeList;
import com.google.gwt.query.client.Function;
import com.google.gwt.query.client.GQuery;
import com.google.gwt.query.client.js.JsCache;
import com.google.gwt.query.client.js.JsMap;
import com.google.gwt.query.client.js.JsNamedArray;
import com.google.gwt.query.client.js.JsObjectArray;
import com.google.gwt.query.client.js.JsUtils;
import com.google.gwt.user.client.DOM;
import com.google.gwt.user.client.Event;
import com.google.gwt.user.client.EventListener;

import java.util.ArrayList;
import java.util.List;

/**
 * This class implements an event queue instance for one Element. The queue instance is configured
 * as the default event listener in GWT.
 *
 * The reference to this queue is stored as a unique variable in the element's DOM
 *
 * The class takes care of calling the appropriate functions for each browser event and it also
 * calls sinkEvents method.
 *
 */
public class EventsListener implements EventListener {

  public interface SpecialEvent {
    String getDelegateType();

    String getOriginalType();

    Function createDelegateHandler(Function originalHandler);
  }

  /**
   * Used for simulating mouseenter and mouseleave events
   */
  public static class MouseSpecialEvent implements SpecialEvent {

    private String originalType;
    private String delegateType;

    public MouseSpecialEvent(String originalType, String delegateType) {
      this.originalType = originalType;
      this.delegateType = delegateType;
    }

    public String getDelegateType() {
      return delegateType;
    }

    public String getOriginalType() {
      return originalType;
    }

    public Function createDelegateHandler(Function originalHandler) {
      return new SpecialMouseEventHandler(originalHandler);
    }
  }

  private interface HandlerWrapper {
    Function getOriginalHandler();
  }
  private static class SpecialMouseEventHandler extends Function implements HandlerWrapper {

    private Function delegateHandler;

    public SpecialMouseEventHandler(Function originalHandler) {
      this.delegateHandler = originalHandler;
    }

    @Override
    public boolean f(Event e, Object data) {
      EventTarget eventTarget = e.getCurrentEventTarget();
      Element target = eventTarget != null ? eventTarget.<Element> cast() : null;

      EventTarget relatedEventTarget = e.getRelatedEventTarget();
      Element related = relatedEventTarget != null ? relatedEventTarget.<Element> cast() : null;

      // For mousenter/leave call the handler if related is outside the target.
      if (related == null || (related != target && !GQuery.contains(target, related))) {
        return delegateHandler != null ? delegateHandler.f(e, data) : false;
      }

      return false;
    }

    public Function getOriginalHandler() {
      return delegateHandler;
    }
  }

  private static class BindFunction {

    Object data;
    Function function;
    String nameSpace = "";
    // for special event like mouseleave, mouseenter
    String originalEventType;
    int times = -1;
    int type;

    BindFunction(int t, String n, String originalEventType, Function f, Object d) {
      type = t;
      function = f;
      data = d;
      this.originalEventType = originalEventType;
      if (n != null) {
        nameSpace = n;
      }
    }

    BindFunction(int t, String n, String originalEventType, Function f, Object d, int times) {
      this(t, n, originalEventType, f, d);
      this.times = times;
    }

    public boolean fire(Event event) {
      if (times != 0) {
        times--;
        return function.fe(event, data);
      }
      return true;
    }

    public boolean hasEventType(int etype) {
      return (type & etype) != 0;
    }

    /**
     * Remove a set of events. The bind function will not be fire anymore for those events
     *
     * @param eventBits the set of events to unsink
     *
     */
    public int unsink(int eventBits) {
      if (eventBits <= 0) {
        type = 0;
      } else {
        type = type & ~eventBits;
      }

      return type;
    }

    @Override
    public String toString() {
      return "bind function for event type " + type;
    }

    public boolean isEquals(Function f) {
      assert f != null : "function f cannot be null";
      Function functionToCompare =
          function instanceof HandlerWrapper ? ((HandlerWrapper) function).getOriginalHandler()
              : function;
      return f.equals(functionToCompare);
    }

    public Object getOriginalEventType() {
      return originalEventType;
    }
  }

  /**
   * {@link BindFunction} used for live() method.
   *
   */
  private static class LiveBindFunction extends BindFunction {

    JsNamedArray<JsObjectArray<BindFunction>> bindFunctionBySelector;

    LiveBindFunction(int type, String namespace) {
      super(type, namespace, null, null, -1);
      clean();
    }

    /**
     * Add a {@link BindFunction} for a specific css selector
     */
    public void addBindFunctionForSelector(String cssSelector, BindFunction f) {
      JsObjectArray<BindFunction> bindFunctions = bindFunctionBySelector.get(cssSelector);
      if (bindFunctions == null) {
        bindFunctions = JsObjectArray.create();
        bindFunctionBySelector.put(cssSelector, bindFunctions);
      }

      bindFunctions.add(f);
    }

    public void clean() {
      bindFunctionBySelector = JsNamedArray.create();
    }

    @Override
    public boolean fire(Event event) {
      if (isEmpty()) {
        return true;
      }

      // first element where the event was fired
      Element eventTarget = getEventTarget(event);
      // last element where the event was dispatched on
      Element liveContextElement = getCurrentEventTarget(event);

      if (eventTarget == null || liveContextElement == null) {
        return true;
      }

      // Compute the live selectors which respond to this event type
      List<String> validSelectors = new ArrayList<String>();
      for (String cssSelector : bindFunctionBySelector.keys()) {
        JsObjectArray<BindFunction> bindFunctions = bindFunctionBySelector.get(cssSelector);
        for (int i = 0; bindFunctions != null && i < bindFunctions.length(); i++) {
          BindFunction f = bindFunctions.get(i);
          if (f.hasEventType(event.getTypeInt())) {
            validSelectors.add(cssSelector);
            break;
          }
        }
      }

      // Create a structure of elements which matches the selectors
      JsNamedArray<NodeList<Element>> realCurrentTargetBySelector =
          $(eventTarget).closest(validSelectors.toArray(new String[0]), liveContextElement);
      // nothing matches the selectors
      if (realCurrentTargetBySelector.length() == 0) {
        return true;
      }

      Element stopElement = null;
      GqEvent gqEvent = GqEvent.create(event);
      for (String cssSelector : realCurrentTargetBySelector.keys()) {
        JsObjectArray<BindFunction> bindFunctions = bindFunctionBySelector.get(cssSelector);
        for (int i = 0; bindFunctions != null && i < bindFunctions.length(); i++) {
          BindFunction f = bindFunctions.get(i);
          if (f.hasEventType(event.getTypeInt())) {
            NodeList<Element> n = realCurrentTargetBySelector.get(cssSelector);
            for (int j = 0; n != null && j < n.getLength(); j++) {
              Element element = n.getItem(j);
              // When an event fired in an element stops bubbling we have to fire also all other
              // handlers for this element bound to this element
              if (stopElement == null || element.equals(stopElement)) {
                gqEvent.setCurrentElementTarget(element);

                if (!f.fire(gqEvent)) {
                  stopElement = element;
                }
              }
            }
          }
        }
      }

      // trick to reset the right currentTarget on the original event on ie
      gqEvent.setCurrentElementTarget(liveContextElement);
      return stopElement == null;
    }

    /**
     * Remove the BindFunction associated to this cssSelector
     */
    public void removeBindFunctionForSelector(String cssSelector, String nameSpace, String originalEventName) {
      if (nameSpace == null && originalEventName == null) {
        bindFunctionBySelector.delete(cssSelector);
      } else {
        JsObjectArray<BindFunction> functions = bindFunctionBySelector.get(cssSelector);

        if (functions == null || functions.length() == 0) {
          return;
        }
        JsObjectArray<BindFunction> newFunctions = JsObjectArray.create();

        for (int i = 0; i < functions.length(); i++) {
          BindFunction f = functions.get(i);
          boolean matchNamespace = nameSpace == null || nameSpace.equals(f.nameSpace);
          boolean matchOriginalEventName = originalEventName == null || originalEventName.equals(f.originalEventType);

          if (!matchNamespace || !matchOriginalEventName) {
            newFunctions.add(f);
          }
        }

        bindFunctionBySelector.delete(cssSelector);
        if (newFunctions.length() > 0) {
          bindFunctionBySelector.put(cssSelector, newFunctions);
        }

      }
    }

    /**
     * Tell if no {@link BindFunction} are linked to this object
     *
     * @return
     */
    public boolean isEmpty() {
      return bindFunctionBySelector.length() == 0;
    }

    @Override
    public String toString() {
      return "live bind function for selector "
          + bindFunctionBySelector.<JsCache> cast().tostring();
    }

    /**
     * Return the element whose the listener fired last. It represent the context element where the
     * {@link LiveBindFunction} was binded
     *
     */
    private Element getCurrentEventTarget(Event e) {
      EventTarget currentEventTarget = e.getCurrentEventTarget();

      if (!Element.is(currentEventTarget)) {
        return null;
      }

      return Element.as(currentEventTarget);
    }

    /**
     * Return the element that was the actual target of the element
     */
    private Element getEventTarget(Event e) {
      EventTarget eventTarget = e.getEventTarget();

      if (!Element.is(eventTarget)) {
        return null;
      }

      return Element.as(eventTarget);
    }

  }

  public static int ONSUBMIT = GqEvent.ONSUBMIT;
  public static int ONRESIZE = GqEvent.ONRESIZE;
  public static String MOUSEENTER = "mouseenter";
  public static String MOUSELEAVE = "mouseleave";

  public static JsMap<String, SpecialEvent> special;

  static {
    special = JsMap.create();
    special.put(MOUSEENTER, new MouseSpecialEvent(MOUSEENTER, "mouseover"));
    special.put(MOUSELEAVE, new MouseSpecialEvent(MOUSELEAVE, "mouseout"));
  }

  public static void clean(Element e) {
    EventsListener ret = getGQueryEventListener(e);
    if (ret != null) {
      ret.clean();
    }
  }

  public static EventsListener getInstance(Element e) {
    EventsListener ret = getGQueryEventListener(e);
    return ret != null ? ret : new EventsListener(e);
  }

  public static void rebind(Element e) {
    EventsListener ret = getGQueryEventListener(e);
    if (ret != null && ret.eventBits != 0) {
      ret.sink();
    }
  }

  private static native void cleanGQListeners(Element elem) /*-{
		if (elem.__gwtlistener) {
      @com.google.gwt.user.client.DOM::setEventListener(*)(elem, elem.__gwtlistener);
		}
		elem.__gwtlistener = elem.__gqueryevent = elem.__gquery = null;
  }-*/;

  private static native EventsListener getGQueryEventListener(Element elem) /*-{
		return elem.__gqueryevent;
  }-*/;

  private static native EventListener getGwtEventListener(Element elem) /*-{
		return elem.__gwtlistener;
  }-*/;

  private static native void init(Element elem, EventsListener gqevent)/*-{
		elem.__gwtlistener = @com.google.gwt.user.client.DOM::getEventListener(*)(elem);
		elem.__gqueryevent = gqevent;
  }-*/;

  // Gwt does't handle submit nor resize events in DOM.sinkEvents
  private static native void sinkEvent(Element elem, String name) /*-{
		if (!elem.__gquery)
			elem.__gquery = [];
		if (elem.__gquery[name])
			return;
		elem.__gquery[name] = true;

		var handle = function(event) {
			elem.__gqueryevent.@com.google.gwt.query.client.plugins.events.EventsListener::dispatchEvent(Lcom/google/gwt/user/client/Event;)(event);
		};

		if (elem.addEventListener)
			elem.addEventListener(name, handle, true);
		else
			elem.attachEvent("on" + name, handle);
  }-*/;

  int eventBits = 0;
  double lastEvnt = 0;

  int lastType = 0;

  private Element element;

  private JsObjectArray<BindFunction> elementEvents = JsObjectArray.createArray().cast();

  private JsMap<Integer, LiveBindFunction> liveBindFunctionByEventType = JsMap.create();

  private EventsListener(Element element) {
    this.element = element;
    init(element, this);
  }

  public void bind(int eventbits, final Object data, Function... funcs) {
    bind(eventbits, null, data, funcs);
  }

  public void bind(int eventbits, final Object data, final Function function, int times) {
    bind(eventbits, null, null, data, function, times);
  }

  public void bind(int eventbits, String name, final Object data, Function... funcs) {
    for (Function function : funcs) {
      bind(eventbits, name, null, data, function, -1);
    }
  }

  public void bind(int eventbits, String namespace, String originalEventType, final Object data,
      final Function function, int times) {
    if (function == null) {
      unbind(eventbits, namespace, originalEventType, null);
      return;
    }
    eventBits |= eventbits;
    sink();
    elementEvents.add(new BindFunction(eventbits, namespace, originalEventType, function, data,
        times));
  }

  public void bind(String events, final Object data, Function... funcs) {
    String[] parts = events.split("[\\s,]+");

    for (String event : parts) {

      String nameSpace = null;
      String eventName = event;

      //seperate possible namespace
      //jDramaix: I removed old regex ^([^.]*)\.?(.*$) because it didn't work on IE8...
      String[] subparts = event.split("\\.", 2);

      if (subparts.length == 2){
        nameSpace = subparts[1];
        eventName = subparts[0];
      }

      //handle special event like mouseenter or mouseleave
      SpecialEvent hook = special.get(eventName);
      eventName = hook != null ? hook.getDelegateType() : eventName;
      String originalEventName = hook != null ? hook.getOriginalType() : null;

      int b = getTypeInt(eventName);
      for (Function function : funcs) {
        Function handler = hook != null ? hook.createDelegateHandler(function) : function;
        bind(b, nameSpace, originalEventName, data, handler, -1);
      }
    }
  }

  public void die(String eventNames, String cssSelector) {
    String[] parts = eventNames.split("[\\s,]+");

    for (String event : parts) {
      String nameSpace = null;
      String eventName = event;

      //seperate possible namespace
      //jDramaix: I removed old regex ^([^.]*)\.?(.*$) because it didn't work on IE8...
      String[] subparts = event.split("\\.", 2);

      if (subparts.length == 2) {
        nameSpace = subparts[1];
        eventName = subparts[0];
      }


      //handle special event like mouseenter or mouseleave
      SpecialEvent hook = special.get(eventName);
      eventName = hook != null ? hook.getDelegateType() : eventName;
      String originalEventName = hook != null ? hook.getOriginalType() : null;

      int b = getTypeInt(eventName);

      die(b, nameSpace, originalEventName, cssSelector);
    }


  }

  public void die(int eventbits, String nameSpace, String originalEventName,String cssSelector) {
    if (eventbits <= 0) {
      for (String k : liveBindFunctionByEventType.keys()) {
        LiveBindFunction liveBindFunction = liveBindFunctionByEventType.<JsCache> cast().get(k);
        liveBindFunction.removeBindFunctionForSelector(cssSelector, nameSpace, null);
        if (liveBindFunction.isEmpty()){
          liveBindFunctionByEventType.<JsCache>cast().delete(k);
        }
      }
    } else {
      LiveBindFunction liveBindFunction = liveBindFunctionByEventType.get(eventbits);
      if (liveBindFunction != null) {
        liveBindFunction.removeBindFunctionForSelector(cssSelector, nameSpace, originalEventName);
      }
      if (liveBindFunction.isEmpty()){
        liveBindFunctionByEventType.remove(eventbits);
      }
    }
  }

  public void dispatchEvent(Event event) {
    int etype = getTypeInt(event.getType());
    String originalEventType = GqEvent.getOriginalEventType(event);

    for (int i = 0, l = elementEvents.length(); i < l; i++) {
      BindFunction listener = elementEvents.get(i);
      if (listener.hasEventType(etype)
          && (originalEventType == null || originalEventType
              .equals(listener.getOriginalEventType()))) {
        if (!listener.fire(event)) {
          event.stopPropagation();
          event.preventDefault();
        }
      }
    }
  }

  /**
   * Return the original gwt EventListener associated with this element, before gquery replaced it
   * to introduce its own event handler.
   */
  public EventListener getOriginalEventListener() {
    return getGwtEventListener(element);
  }

  public void live(String events, String cssSelector, Object data, Function... funcs) {

    String[] parts = events.split("[\\s,]+");

    for (String event : parts) {

      String nameSpace = null;
      String eventName = event;


      String[] subparts = event.split("\\.", 2);

      if (subparts.length == 2) {
        nameSpace = subparts[1];
        eventName = subparts[0];
      }

      //handle special event like mouseenter or mouseleave
      SpecialEvent hook = special.get(eventName);
      eventName = hook != null ? hook.getDelegateType() : eventName;
      String originalEventName = hook != null ? hook.getOriginalType() : null;

      int b = getTypeInt(eventName);
      for (Function function : funcs) {
        Function handler = hook != null ? hook.createDelegateHandler(function) : function;
        live(b, nameSpace, originalEventName, cssSelector, data, handler);
      }
    }
  }

  public void live(int eventbits, String nameSpace, String originalEventName, String cssSelector, Object data, Function... funcs) {
    for (int i = 0; i < 28; i++) {
      int event = (int) Math.pow(2, i);
      if ((eventbits & event) == event) {

        // is a LiveBindFunction already attached for this kind of event
        LiveBindFunction liveBindFunction = liveBindFunctionByEventType.get(event);
        if (liveBindFunction == null) {
          liveBindFunction = new LiveBindFunction(event, "live");
          eventBits |= event;
          sink();
          elementEvents.add(liveBindFunction);
          liveBindFunctionByEventType.put(event, liveBindFunction);
        }

        for (Function f : funcs) {
          liveBindFunction.addBindFunctionForSelector(cssSelector, new BindFunction(event, nameSpace,
              originalEventName, f, data));
        }
      }
    }
  }

  public void onBrowserEvent(Event event) {
    double now = Duration.currentTimeMillis();
    // Workaround for Issue_20
    if (lastType == event.getTypeInt() && now - lastEvnt < 10
        && "body".equalsIgnoreCase(element.getTagName())) {
      return;
    }
    lastEvnt = now;
    lastType = event.getTypeInt();

    // Execute the original Gwt listener
    if (getOriginalEventListener() != null) {
      getOriginalEventListener().onBrowserEvent(event);
    }

    dispatchEvent(event);
  }

  public void unbind(int eventbits) {
    unbind(eventbits, null, null, null);
  }

  public void unbind(int eventbits, String namespace, String originalEventType, Function f) {
    JsObjectArray<BindFunction> newList = JsObjectArray.createArray().cast();
    for (int i = 0; i < elementEvents.length(); i++) {
      BindFunction listener = elementEvents.get(i);

      boolean matchNS =
          namespace == null || namespace.isEmpty() || listener.nameSpace.equals(namespace);
      boolean matchEV = eventbits <= 0 || listener.hasEventType(eventbits);
      boolean matchOEVT =
          (originalEventType == null && listener.getOriginalEventType() == null)
              || (originalEventType != null && originalEventType.equals(listener
                  .getOriginalEventType()));
      boolean matchFC = f == null || listener.isEquals(f);

      if (matchNS && matchEV && matchFC && matchOEVT) {
        int currentEventbits = listener.unsink(eventbits);

        if (currentEventbits == 0) {
          // the BindFunction doesn't listen anymore on any events
          continue;
        }
      }

      newList.add(listener);
    }
    elementEvents = newList;

  }

  public void unbind(String events, Function f) {

    String[] parts = events.split("[\\s,]+");

    for (String event : parts) {
      String nameSpace = null;
      String eventName = event;

      //seperate possible namespace
      //jDramaix: I removed old regex ^([^.]*)\.?(.*$) because it didn't work on IE8...
      String[] subparts = event.split("\\.", 2);

      if (subparts.length == 2){
        nameSpace = subparts[1];
        eventName = subparts[0];
      }

      //handle special event
      SpecialEvent hook = special.get(eventName);
      eventName = hook != null ? hook.getDelegateType() : eventName;
      String originalEventName = hook != null ? hook.getOriginalType() : null;

      int b = getTypeInt(eventName);

      unbind(b, nameSpace, originalEventName, f);
    }
  }

  private void clean() {
    cleanGQListeners(element);
    elementEvents = JsObjectArray.createArray().cast();
    liveBindFunctionByEventType = JsMap.create();
  }

  private void sink() {
    // ensure that the gwtQuery's event listener is set as event listener of the element
    DOM.setEventListener((com.google.gwt.user.client.Element) element, this);
    if (eventBits == ONSUBMIT) {
      sinkEvent(element, "submit");
    } else if ((eventBits | ONRESIZE) == ONRESIZE) {
      sinkEvent(element, "resize");
    } else {
      if ((eventBits | Event.FOCUSEVENTS) == Event.FOCUSEVENTS 
          && JsUtils.isElement(element)
          && element.getAttribute("tabIndex").length() == 0) {
        element.setAttribute("tabIndex", "0");
      }
      DOM.sinkEvents((com.google.gwt.user.client.Element) element, eventBits
          | DOM.getEventsSunk((com.google.gwt.user.client.Element) element));

    }
  }

  private int getEventBits(String... events) {
    int ret = 0;
    for (String e : events) {
      String[] parts = e.split("[\\s,]+");
      for (String s : parts) {
        int event = getTypeInt(s);
        if (event > 0) {
          ret |= event;
        }
      }
    }

    return ret;
  }

  private int getTypeInt(String eventName) {
    return "submit".equals(eventName) ? ONSUBMIT : "resize".equals(eventName) ? ONRESIZE : Event
        .getTypeInt(eventName);
  }

  public void cleanEventDelegation() {
    for (String k : liveBindFunctionByEventType.keys()) {
      LiveBindFunction function = liveBindFunctionByEventType.<JsCache> cast().get(k);
      function.clean();
    }
  }

}
