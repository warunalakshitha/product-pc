/*
 *  Copyright (c) 2005-2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */
$(function () {

    var SEARCH_API = '/apis/assets?q=';
    var SEARCH_BUTTON = '#search-btn';
    var SEARCH_FORM = '#search-form';
    var rows_added = 0;
    var last_to = 0;
    var items_per_row = 0;
    var doPagination = true;
    var options = [];
    var tags = [];

    window.onload = function () {
        $.ajax({
            url: '/designer/assets/process/apis/get_process_tags',
            type: 'GET',
            success: function (data) {
                var response = JSON.parse(data);
                if (response.error === false) {
                    processTagsObj = JSON.parse(response.content);
                    if (!$.isEmptyObject(processTagsObj)) {
                        for (var key in processTagsObj) {
                            if (processTagsObj.hasOwnProperty(key)) {
                                tags.push(key);
                            }
                        }
                    }

                    $('#tags').tokenfield({
                        autocomplete: {
                            source: tags,
                            delay: 100
                        },
                        showAutocompleteOnFocus: true
                    });

                    $('#tags').on('tokenfield:createtoken', function (event) {
                        var existingTokens = $(this).tokenfield('getTokens');
                        $.each(existingTokens, function (index, token) {
                            if (token.value === event.attrs.value)
                                event.preventDefault();
                        });
                    });
                } else {
                    alertify.error(response.content);
                }
            },
            error: function () {
                alertify.error('Process list returning error');
            }
        });
    };


    store.infiniteScroll = {};
    store.infiniteScroll.recalculateRowsAdded = function () {
        return (last_to - last_to % items_per_row) / items_per_row;
    };
    store.infiniteScroll.addItemsToPage = function (query) {

        var screen_width = $(window).width();
        var screen_height = $(window).height();


        var header_height = 400;
        var thumb_width = 170;
        var thumb_height = 280;
        var gutter_width = 20;

        screen_width = screen_width - gutter_width; // reduce the padding from the screen size
        screen_height = screen_height - header_height;

        items_per_row = (screen_width - screen_width % thumb_width) / thumb_width;
        //var rows_per_page = (screen_height-screen_height%thumb_height)/thumb_height;
        var scroll_pos = $(document).scrollTop();
        var row_current = (screen_height + scroll_pos - (screen_height + scroll_pos) % thumb_height) / thumb_height;
        row_current += 3; // We increase the row current by 2 since we need to provide one additional row to scroll down without loading it from backend


        var from = 0;
        var to = 0;
        if (row_current > rows_added && doPagination) {
            from = rows_added * items_per_row;
            to = row_current * items_per_row;
            last_to = to; //We store this os we can recalculate rows_added when resolution change
            rows_added = row_current;
            store.infiniteScroll.getItems(from, to, query);
            //console.info('getting items from ' + from + " to " + to + " screen_width " + screen_width + " items_per_row " + items_per_row);
        }

    };
    store.infiniteScroll.getItems = function (from, to, query) {

        var count = to - from;
        var dynamicData = {};
        dynamicData["from"] = from;
        dynamicData["to"] = to;
        var path = window.location.href; //current page path
        // Returns the jQuery ajax method
        var url = caramel.tenantedUrl(SEARCH_API + query + "&paginationLimit=" + to + "&start=" + from + "&count=" + count);

        caramel.render('loading', 'Loading assets from ' + from + ' to ' + to + '.', function (info, content) {
            $('.loading-animation-big').remove();
            $('body').append($(content));
        });

        $.ajax({
            url: url,
            method: 'GET',
            success: function (data) {
                var results = [];
                if (data) {
                    results = data.list || [];
                }
                for (var i = 0; i < results.length; i++) {
                    results[i].showType = true;
                }
                if (results.length == 0) {
                    if (from == 0) {
                        alertify.error('We are sorry but we could not find any matching assets');
                    }
                    $('.loading-animation-big').remove();
                    doPagination = false;
                } else {
                    //content specified by user.
                    if ($("#content").val()) {
                        contentSearch(results);
                    }
                    //content search not specified.
                    else {
                        loadPartials('list-assets', function (partials) {
                            caramel.partials(partials, function () {
                                caramel.render('list_assets_table_body', results, function (info, content) {
                                    $('#search-results').append($(content));
                                    $('.loading-animation-big').remove();
                                });
                            });
                        });
                    }
                }
            }, error: function () {
                doPagination = false;
                $('.loading-animation-big').remove();
            }
        });
    };
    store.infiniteScroll.showAll = function (query) {
        store.infiniteScroll.addItemsToPage(query);
        $(window).scroll(function () {
            store.infiniteScroll.addItemsToPage(query);
        });
        $(window).resize(function () {
            //recalculate "rows_added"
            rows_added = store.infiniteScroll.recalculateRowsAdded();
            store.infiniteScroll.addItemsToPage(query);
        });
    };

    var processInputField = function (field) {
        var result = field;
        switch (field.type) {
            case 'text':
                result = field;
                break;
            default:
                break;
        }
        return result;
    };
    var getInputFields = function () {
        var obj = {};
        var fields = $(SEARCH_FORM).find(':input:not(#content)');
        var field;
        for (var index = 0; index < fields.length; index++) {
            field = fields[index];
            field = processInputField(field);
            if ((field.name) && (field.value)) {
                obj[field.name] = field.value;
            }
        }
        return obj;
    };
    var createQueryString = function (key, value) {
        return '"' + key + '":"' + value + '"';
    };
    var buildQuery = function () {
        var fields = getInputFields();
        var queryString = [];
        var value;
        for (var key in fields) {
            value = fields[key];
            queryString.push(createQueryString(key, value));
        }
        return queryString.join(',');
    };
    var isEmptyQuery = function (query) {
        query = query.trim();
        return (query.length <= 0);
    };
    var loadPartials = function (partial, done) {
        $.ajax({
            url: caramel.url('/apis/partials') + '?partial=' + partial,
            success: function (data) {
                done(data);
            },
            error: function () {
                done(err);
            }
        });
    };
    $(SEARCH_BUTTON).on('click', function (e) {
        e.preventDefault();
        doPagination = true;
        rows_added = 0;
        $('#search-results').html('');
        if ($("#content").val() && jQuery.isEmptyObject(options)) {
            alertify.error("Please select content-type");
            $('.loading-animation-big').remove();
            doPagination = false;
            return;
        }
        var query = buildQuery();
        if (isEmptyQuery(query) && !$("#content").val()) {
            alertify.error('User has not entered anything');
            return;
        }
        else if (isEmptyQuery(query) && $("#content").val()) {

            $.ajax({
                url: '/designer/apis/assets',
                method: 'GET',
                success: function (data) {
                    var results = [];
                    if (data) {
                        results = data.list || [];
                    }
                    for (var i = 0; i < results.length; i++) {
                        results[i].showType = true;
                    }
                    if (results.length == 0) {
                        if (from == 0) {
                            alertify.error('We are sorry but we could not find any matching assets');
                        }
                        $('.loading-animation-big').remove();
                        doPagination = false;
                    } else {
                        //content specified by user.
                        if ($("#content").val()) {
                            contentSearch(results);
                        }
                    }
                }, error: function () {
                    doPagination = false;
                    $('.loading-animation-big').remove();
                }
            });
            //   contentSearch(null);
        }
        else {
            store.infiniteScroll.showAll(query);
        }
    });


    function contentSearch(rxt_results) {

        var content = $("#content").val().trim();
        var media = JSON.stringify(options);
        var search_url = caramel.tenantedUrl('/apis/search');

        caramel.render('loading', 'Loading assets ', function (info, content) {
            $('.loading-animation-big').remove();
            $('body').append($(content));
        });
        $.ajax({
            url: search_url,
            type: 'POST',
            data: {
                'search-query': content,
                'mediatype': media
            },
            success: function (data) {

                try {
                    var response = JSON.parse(data);
                    if (response.error === false) {
                        var results = JSON.parse(response.content);

                        //      if (rxt_results) {                     //get the intersection of the two searches.

                        var hashmap = {};
                        var intersection = [];
                        for (var i = 0; i < rxt_results.length; i++) {
                            var pid = rxt_results[i].id;
                            hashmap[pid] = rxt_results[i];
                        }
                        for (var i = 0; i < results.length; i++) {

                            var key = results[i].id;
                            if (hashmap.hasOwnProperty(key)) {
                                intersection.push(hashmap[key]);
                            }
                        }
                        results = intersection;
                        //        }

                        loadPartials('list-assets', function (partials) {
                            caramel.partials(partials, function () {
                                caramel.render('list_assets_table_body', results, function (info, content) {
                                    $('#search-results').append($(content));
                                    $('.loading-animation-big').remove();
                                });
                            });
                        });
                    }
                    else {
                        alertify.error(response.content);
                        $('.loading-animation-big').remove();
                        doPagination = false;
                    }
                } catch (e) {
                    alertify.error("We are sorry but we could not find any matching assets");
                }
            }, error: function (xhr, status, error) {
                alertify.error(error);
                doPagination = false;
                $('.loading-animation-big').remove();
            }
        });
    }

    $('.dropdown-menu a').on('click', function (event) {

        var $target = $(event.currentTarget),
            val = $target.attr('data-value'),
            $inp = $target.find('input'),
            idx;

        if (( idx = options.indexOf(val) ) > -1) {
            options.splice(idx, 1);
            setTimeout(function () {
                $inp.prop('checked', false)
            }, 0);
        } else {
            options.push(val);
            setTimeout(function () {
                $inp.prop('checked', true)
            }, 0);
        }

        $(event.target).blur();
        return false;
    });
});