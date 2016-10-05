(ns untangled-tutorial.H-Server-Interactions
  (:require-macros [cljs.test :refer [is]])
  (:require [om.next :as om :refer-macros [defui]]
            [om.dom :as dom]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]))

; TODO: Lots of TODO items below
; TODO: Modifying a remote AST (e.g. add parameters to query)
; TODO: WHY you don't return a value from a mutation (ask Ethan for my "blog" post on this)
; TODO: Explain Om's HATEOS (keyword listing in :keys)
; see: https://github.com/omcljs/om/wiki/Quick-Start-%28om.next%29
; TODO: Error handling (UI and server), exceptions, fallbacks, status codes
; Remember that global-error-handler is a function of the network impl
; TODO: (advanced) cookies/headers (needs extension to U.Server see issue #13)

(defcard-doc
  "
  # Server interaction

  The semantics of server request processing in Untangled have a number of guarantees
  that Om does not (out of the box) provide:

  - Networking is provided
  - All network requests (queries and mutations) are processed sequentially. This allows you
  to reason about optimistic updates (Starting more than one at a time via async calls could
  lead to out-of-order execution, and impossible-to-reason-about recovery from errors).
  - You may provide fallbacks that indicate error-handling mutations to run on failures

  ## Reads

  Remote reads in Untangled are explicit calls to the load functions in the `untangled.client.data-fetch` namespace, of
  which there are two pairs:

  1. `load-data` and `load-data-action`
  2. `load-field` and `load-field-action`

  ### Load Data vs. Load Field

  Both of these function families are calls to the built-in `untangled/load` mutation, so requests made by either function
  go through the same networking layer and have the same customizations through their named parameters. The only difference
  is that `load-data` must be passed a complete query while `load-field` uses the passed-in component to create its query.
  The following two loads are equivalent:

  ```
  (defui Person
    static om/IQuery (query [this] [:db/id :name :age])
    static om/Ident (ident [this props] [:person/by-id (:db/id props)])
    Object
    (render [this]
      (dom/div #js {:onClick #(df/load-field this :age)})
      (dom/div #js {:onClick #(df/load-data this [:age]
                                :ident (om/get-ident this) :refresh [(om/get-ident this)])}))
  ```

  So, `load-field` focuses the component's query to the specified field, associates the component's ident with the query,
  and asks the UI to re-render all components with the component's ident after the load is complete. Since `load-field` requires
  a component to build the query sent to the server, it cannot be used with the reconciler. If you want to load data
  from your server when the app initially loads, you must use `load-data` with the reconciler passed to the
  `:started-callback` function when creating a new untangled client application.

  ### Load vs. Load-Action

  The non action-suffixed function of each pair calls `om/transact!` under the hood to the built-in untangled mutation called
  `untangled/load`, which is responsible for sending your request to the server. The action-suffixed function of each pair
  **does not** call `om/transact!`, and is used to initialize a load inside of one of your custom client-side mutations
  that you would like to happen just before the data is loaded. Each action-suffixed load function must be used in
  conjunction with `untangled.client.data-fetch/remote-load`, which converts the remote mutation key for your mutation
  to the `untangled/load` key.

  Let's look at an example. Say you want to load a list of people from the server:
  ```
  (require [untangled.client.data-fetch :as df])

  (defui Person ... )
  (def ui-person (om/factory Person))

  (defui PeopleList
    static om/IQuery (query [this] [:db/id :list-title {:people (om/get-query Person}]
    static om/Ident (ident [this props] [:people-list/by-id (:db/id props)])
    Object
    (render [this]
      (let [{:keys [people]} (om/props this)]
        ;; people starts out as nil
        (dom/div nil
          (df/lazily-loaded #(map ui-person %) people
            :not-present-render #(dom/button #js {:onClick #(df/load-field this :people)}
                                   \"Load People\"))))))
  ```

  Since we are in the UI and not inside of a mutation's action thunk, we want to use `load-field` to initialize the
  call to `om/transact!`. The use of `lazily-loaded` above will show a button to load people when `people` is `nil`
  (for example, when the app initially loads), and will render each person in the list of people once the button is
  clicked and the data has been loaded.

  TODO: What if we want to load data when we switch to a new view? Then we have to change the view AND load data
  to show it. We don't want to put loads in lifecycle methods (link to ref guide), so we have to integrate the UI
  switch somehow with the data loading process.

  ### UI attributes

  Untangled recognizes the need to separate attributes that are UI-only and those that should actually be sent to
  the server. If a component, for example, wants to query for:

  ```
  [:ui/popup-visible :db/id :item/name]
  ```

  where the `popup-visible` item is actually in app state (a useful thing to do with most state instead of making
  it component local), then you have a problem when that component is composed into another component that
  is to be used when generating a server query. You don't want the UI-specific attributes to *go* to the server!

  Untangled handles this for you. Any attributes in your component queries that are namespaced to `ui` are automatically
  (and recursively) stripped from queries before they are sent to the server. There is nothing for you to do except
  namespace these local-only attributes in the queries! (Additionally, there are local mutation
  helpers that can be used to update these without writing custom mutation code. See the section on Mutation)

  ### Data merge

  When the server responds Untangled will merge the result into the application client database. It overrides the built-in Om
  shallow merge. Untangled's data merge has a number of extension that are useful for
  simple application reasoning:

  1. Merge is a deep merge, but with extra logic

  Untangled merges your response via deep merge, meaning that existing data is not wiped out by default. Unfortunately,
  this causes a different problem. Let's say you have two UI components that ask for similar information:

  Component A asks for [:a]
  Component A2 asks for [:a :b]

  Of course, these queries are composed into a larger query, but you can imagine that if we use the query of A2, normalization
  will put something like this somewhere in the app state: `{ :a 1 :b 2}`. Now, at a later time, say we re-run a load but
  use component A's query. The response from the server will say something like `{:a 5}`, because all we asked for was
  `:a`!  But what if both A and A2 are on the screen??? Well, depending on how you merge strange things can happen.

  So, Untangled forms an opinion on this scenario:

  - First, since it isn't a great thing to do, you should avoid it
  - However, if you do it, Untangled merges with the following rules:
      - If the query *asks* for an attribute, and the *response does not include it*, then it is always removed from the app state since the
      server has clearly indicated it is gone.
      - If the query *does not ask* for an attribute (which means the response cannot possibly contain it), then Untangled
      will avoid removing it, even if other attributes come back (e.g. it will be a merge leaving the property that was
      not asked for alone). This does indicate that your UI is possibly in a state inconsistent with the server, which
      is the reason for the \"avoid this case\" advice.

  ### Normalization

  Normalization is always *on* in Untangled. You are forced to use the default database format. If you've passed an
  atom as initial state then initial state is assumed to be pre-normalized, but normalization will always be on. Loads
  must use real composed queries from the UI for normalization to work (the om `get-query` function adds info to assit
  with normalization).

  Therefore, you almost *never* want to use a hand-written query that has not been placed on a `defui`. It is perfectly
  acceptable to define queries via `defui` to ensure normalization will work, and this will commonly be the case if your
  UI needs to ask for data in a structure different from what you want to run against the server.

  ### Query narrowing

  The load functions allow you to elide parts of the query using a `:without` set. This is useful when you have a query
  that would load way more than you need right now. Using the `:without` parameter on a `load` function will cause it
  to elide the portions of the query (properties/joins) that use the given keywords. See the loading sections below.

  ## Mutations

  ### Optimistic (client) changes

  NOTE: Om action thunks are executed BEFORE the remote read, so if you want to delete data from the client that you
  need to pass to the server, you will have to keep track of that state before removing it permanently.

  TODO: Just like Om

  ### Server writes

  TODO: Just like Om (`:remote true` or `:remote ast`); however Untangled adds in error handling triggering via tx fallbacks:

  ```
  (om/transact! this '[(app/f) (tx/fallback {:action mutation-symbol :param 1})])
  ```

  assuming the `app/f` mutation returns `{:remote true}` (or `{:remote AST}`)  this sends `app/f` to the server.
  If the server throws an error
  (via ex-info) then the fallback action's mutation symbol (a dispatch key for mutate) is invoked on the client with
  params that include the client fallback params (`:param 1` in the example) and an `:error` key that includes the
  details of the server exception (error type, message, and ex-info's data). Be sure to only include serializable data
  in the server exception!

  You can have any number of fallbacks in a tx, and they will run in order if the transaction fails.

  TODO: Clearing the remaining send queue, etc. The API does not support (but needs to) optional clearing of
  the remainder of the send queue on the client as part of fallback handling. This might be necessary, say, in the case
  where the tx that failed indicates the app state is invalid...additional network interactions are probably all going to
  fail. What you want to do is trigger some kind of state reload to restore sanity.

  #### Updating an existing item

  TODO: Just like Om

  #### New item creation – Temporary IDs

  TODO: Similar to Om, but `{ :tempids tempidmap }` can be returned from `:action` of server mutation and it will
  just work. The plumbing is pre-written.

  #### Reading results after a mutation

  See notes on built-in call (untangled/load)

  ## Differences from stock Om (Next)

  For those that are used to Om, you may be interested in the differences and rationale behind the way Untangled
  handles server interactions, particularly remote reads. There is no Om parser to do remote reads on the client side.

  In Om, you have a fully customizable experience for reads/writes; however, to get this power you must write
  a parser to process the queries and mutations, including analyzing application state to figure out when to talk
  to a remote. While this is fully general and quite flexible (Untangled is implemented on top of it, after all),
  there is a lot of head scratching to get the result you want.

  Our realization when building Untangled was that remote reads happen in two basic cases: initial load (based on
  initial application state) and event-driven load (e.g. routing, \"load comments\", etc). Then we had a few more
  facts that we threw into the mix:

  - We very often wanted to morph the UI query in some way before sending it to the server (e.g. process-roots or
  asking for a collection, but then wanting to query it in the UI \"grouped by category\").
      - This need to modify the query (or write server code that could handle various different configurations of the UI)
        led us to the realization that we really wanted a table in our app database on canonical data, and \"views\" of
        that data (e.g. a sorted page of it, items grouped by category, etc.) While you can do this with the parser, it
        is crazy complicated compared to the simple idea: Any time you load data into a given table, allow the user to
        regenerate derived views in the app state, so that the UI queries just naturally work without parsing logic for
        *each* re-render. The con, of course, is that you have to keep the derived \"views\" up to date, but this is
        much easier to reason about (and codify into a central update function) in practice than a parser.
  - We always needed a marker in the app state so that our remote parsing code could *decide* to return a remote query.
      - The marker essentially needed to be a state machine kind of state marker (ready to load, loading in progress,
        loading failed, data present). This was a complication that would be repeated over and over.
  - We often wanted a *global* marker to indicate when network activity was going on

  By eliminating the need for an Om parser to process all of this and centralizing the logic to a core set of functions
  that handle all of these concerns you gain a lot of simplicity.

  So, let's look how we handle the explicit use-cases:

  ### Use case - Initial load

  In Om, you'd write a parser, set some initial state indicating 'I need to load this', and in your parser you'd return
  a remote `true` for that portion of the read when you hit it. The intention would then be that the server returning
  data would overwrite that marker and the resulting re-render would update the UI. If your UI query doesn't match what
  your server wants to hear, then you either write multiple UI-handling bits on the server, or you pre/post process the
  ROOT-centric query in your send function. Basically, you write a lot of plumbing. Server error handling is completely
  up to you in your send method.

  In Untangled, initial load is an explicit step. You simply put calls to `load-data` in your app start callback.
  State markers are put in place that allow you to then render the fact that you are loading data. Any number of separate
  server queries can be queued, and the queries themselves are used for normalization. Post-processing of the response
  is well-defined and trivial to access.

  ```
  (uc/new-untangled-client
    :initial-state {}
    :started-callback
      (fn [app]
        (df/load-data :query [{:items (om/get-query CollectionComponent)}]
                                     :without #{:comments}
                                     :post-mutation 'app/build-views)))
  ```

  In the above example the client is created (which must be mounted as a separate step). Once mounted the application
  will call the `:started-callback` which in turn will trigger a load. This helper function is really a call to
  om `transact!` that places `ready-to-load` markers in the app state, which in turn triggers the network plumbing. The
  network plumbing pulls these and processes them via the server and all of the normalization bits of Om (and Untangled).

  The `:without` parameter will elide portions of the query. So for example, if you'd like to lazy load some portion of the
  collection (e.g. comments on each item) at a later time, you can prevent the server from being asked.

  The `:post-mutation` parameter is the name of the mutation you'd like to run on a successful result of the query. If there
  is a failure, then a failure marker will be placed in the app state, which you can have programmed your UI to react to
  (e.g. showing a dialog that has user-driven recovery choices).


  ### Use case - Lazy loading

  The other major case is wanting to load data in response to a user interaction. Interestingly, the query that you might
  have used in the initial load use case might have included UI queries for data you didn't want to fetch yet. So, we want
  to note that the initial load use-case supports eliding part of the query. For example, you can load an item without,
  say, comments. Later, when the user wants to see comments you can supply a button that can load the comments on demand.

  This is directly supported by `load-field`, which derives the query to send to the server from the component itself!

  ```
  (load-field this :comments)
  ```

  The only requirements are that the component has an Ident and the query for the component includes a join or property
  named `:comments` in the query.

  For example, say you had:

  ```
  (defui Item
     static om/IQuery
     (query [this] [:id :value {:comments (om/get-query Comment)}])
     static om/Ident
     (ident [this props] [:item/by-id (:id props)])
     Object
     (render [this]
        ...
        (dom/button #js { :onClick #(df/load-field this :comments) } \"Load comments\")
        ...)
  ```

  then clicking the button will result in the following query to the server:

  ```
  [{[:item/by-id 32] [{:comments [:other :props]]}]
  ```

  and the code to write for the server is now trivial. The dispatch key is :item/by-id, the 32 is accessible on the AST,
  and the query is a pull fragment that will work relative to an item in your (assuming Datomic) database!

  Furthermore, the underlying code can easily put a marker in place of that data in the app state so you can show the
  'load in progress' marker of your choice.

  Untangled has supplied all of the Om plumbing for you.

  #### How reads work : `untangled/load`

  The helper functions described above simply trigger a built-in Untangled mutation called `untangled/load`, which you are
  allowed (and sometimes encouraged) to use directly. It is the Untangled method of doing follow-on reads after a remote
  mutation:

  ```
  (om/transact! this '[(app/do-some-thing) (untangled/load {:query [:a]})])
  ```

  The normal form of follow-on keywords (for re-rendering the UI) works fine, it will just never trigger remote
  reads.

  The `untangled/load` mutation does a very simple thing: It puts a state marker in a well-known location in your app state
  to indicate that you're wanting to load something (and returns `:remote true`). This causes the network
  plumbing to be triggered. The network plumbing only receives mutations that are marked remote, so it does the following:

  - It looks for the special mutations `untangled/load` and `tx/fallback`. The latter is part of the unhappy path handling.
     - For each load, it places a state marker in the app state at the target destination for the query data
     - All loads that are present are combined together into a single Om query
  - It looks for other mutations
  - It puts the 'other mutations' on the send queue
  - It puts the derived query from the `untangled/load` onto the send queue

  A separate \"thread\" (core async go block) watches the send queue, and sends things one-at-a-time (e.g. each entry
  in the queue is processed in a sequence, ensuring you can reason about things sequentially). The one-at-a-time
  semantics are very important for handling tempid reassignment, rational optimistic updates, and unhappy path handling.

  The send processing block (uses core async to make a thread-like infinite loop):

  - Pulls an item from the queue (or \"blocks\" when empty)
  - Sends it over the network
  - Updates the marker in the app state to `loading` (which causes a re-render, so you can render loading UI)
  - \"waits\" for the response
      - On success: merges the data
      - On error: updates the state marker to an error state (which re-renders allowing the UI to show error UI)
  - Repeats in an infinite loop

  #### Using `untangled/load` directly

  TODO: See the helper functions `load-data` and `load-field`. We might add more specific versions of `untangled/load`
  that provide a clearer end-user API.

  ### Remote reads after a mutation

  In Om, you can list properties after your mutation to indicate re-renders. You can force them to be remote reads by
  quoting them. All of this requires complex logic in your parser to compare flags on the AST, process the resulting
  query in send (e.g. via process-roots), etc. It is more flexible, but the very common case can be made a lot more direct.

  ```
  (om/transact! this '[(app/f) ':thing]) ; run mutation and re-read thing on the server...
  ; BUT YOU implement the logic to make sure it is understood that way!
  ```

  In Untangled, follow-on keywords are always local re-render reads, and nothing more:

  ```
  (om/transact! this '[(app/f) :thing]) ; Om and Untangled: Do mutation, and re-render anything that has :thing in a query
  ```

  Instead, we supply access to the internal mutation we use to queue loads, so that remote reads are simple and explicit:

  ```
  ; Do mutation, then run a remote read of the given query, along with a post-mutation to alter app state when the load is complete
  (om/transact! this `[(app/f) (untangled/load {:query ~(om/get-query Thing) :post-mutation after-load-sym}])
  ```

  Of course, you can (and *should) use syntax quoting to embed a query from (om/get-query) so that normalization works,
  and the post-mutation can be used to deal with the fact that other parts of the UI may want to, for example, point
  to this newly-loaded thing. The `after-load-sym` is a symbol (e.g. dispatch key to the mutate multimethod). The multi-method
  is guaranteed to be called with the app state in the environment, but no other Om environment items are ensured at
  the moment.


  ### Global network activity marker

  TODO: Document how to use the network activity marker to show a general purpose loading marker in the UI. Basically query
  to the top-level (via an Om query link).
")
