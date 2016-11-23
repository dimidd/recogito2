require.config({
  baseUrl: "/assets/javascripts/",
  fileExclusionRegExp: /^lib$/
});

require([
  'common/api',
  'common/config',
  'document/annotation/common/editor/editorRead',
  'document/annotation/common/editor/editorWrite',
  'document/annotation/common/baseApp',
  'document/annotation/table/selection/highlighter',
  'document/annotation/table/selection/selectionHandler'
], function(
  API,
  Config,
  ReadEditor,
  WriteEditor,
  BaseApp,
  Highlighter,
  SelectionHandler) {

  var App = function(results) {

    var containerNode = document.getElementById('table-container'),

        dataView = new Slick.Data.DataView(),

        options = {
          enableCellNavigation:true,
          enableColumnReorder:false,
          fullWidthRows:true,
          defaultColumnWidth:120,
          rowHeight:34,
          frozenColumn:1
        },

        // Default leading columns
        frozenColumns = [
          { id: '__annotation', name: '', field: '__annotation', width: 90, formatter: Highlighter.CellFormatter },
          { id: 'id', name: '#', field: 'id', width: 50, sortable: true }
        ],

        columns = jQuery.map(results.meta.fields, function(f) {
          return { id: f, name: f, field: f, sortable: true };
        }),

        grid = new Slick.Grid('#table', dataView, frozenColumns.concat(columns), options),

        data = jQuery.map(results.data, function(f, idx) {
          f.id = idx; // ID field required by dataView - we'll reuse it for the index
          return f;
        }),

        highlighter = new Highlighter(grid),

        selector = new SelectionHandler(grid, highlighter),

        editor = (Config.writeAccess) ?
          new WriteEditor(containerNode, selector) :
          new ReadEditor(containerNode),

        onRowCountChanged = function(e, args) {
          grid.updateRowCount();
          grid.render();
        },

        onRowsChanged = function(e, args) {
          grid.invalidateRows(args.rows);
          grid.render();
        },

        onSort = function(e, args) {
          var comparator = function(a, b) {
                var fieldA = a[args.sortCol.field],
                    fieldB = b[args.sortCol.field];
                return (fieldA == fieldB ? 0 : (fieldA > fieldB ? 1 : -1));
              };

          dataView.sort(comparator, args.sortAsc);
        };

    grid.onSort.subscribe(onSort);

    dataView.onRowCountChanged.subscribe(onRowCountChanged);
    dataView.onRowsChanged.subscribe(onRowsChanged);
    dataView.setItems(data);

    BaseApp.apply(this, [ containerNode, highlighter, selector ]);

    selector.on('select', editor.openSelection);

    editor.on('createAnnotation', this.onCreateAnnotation.bind(this));
    editor.on('updateAnnotation', this.onUpdateAnnotation.bind(this));
    editor.on('deleteAnnotation', this.onDeleteAnnotation.bind(this));

    jQuery(window).resize(function() { grid.resizeCanvas(); });

    API.listAnnotationsInPart(Config.documentId, Config.partSequenceNo)
       .done(this.onAnnotationsLoaded.bind(this))
       .fail(this.onAnnotationsLoadError.bind(this));
  };
  App.prototype = Object.create(BaseApp.prototype);

  /** On page load, fetch and parse the CSV and instantiate the app **/
  jQuery(document).ready(function() {
    var dataURL = jsRoutes.controllers.document.DocumentController
          .getDataTable(Config.documentId, Config.partSequenceNo).absoluteURL(),

        onLoadComplete = function(results) {
          new App(results);
        },

        onLoadError = function(error) {
          // TODO implement
        };

    Papa.parse(dataURL, {
      download : true,
      header   : true, // TODO can we make this configurable through extra table meta?
      error    : onLoadError,
      complete : onLoadComplete
    });
  });

});