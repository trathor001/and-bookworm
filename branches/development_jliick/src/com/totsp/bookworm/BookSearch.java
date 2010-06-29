package com.totsp.bookworm;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.View.OnClickListener;
import android.view.View.OnKeyListener;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AbsListView.OnScrollListener;
import android.widget.AdapterView.OnItemClickListener;

import com.totsp.bookworm.data.GoogleBookDataSource;
import com.totsp.bookworm.model.Book;
import com.totsp.bookworm.util.BookUtil;
import com.totsp.bookworm.util.StringUtil;
import com.totsp.bookworm.util.TaskUtil;

import java.net.URLEncoder;
import java.util.ArrayList;

public class BookSearch extends Activity {

   public static final String FROM_SEARCH = "FROM_SEARCH";

   BookWormApplication application;

   EditText searchInput;
   Button searchButton;
   ListView searchResults;

   int selectorPosition;
   int searchPosition;

   String currSearchTerm = "";
   String prevSearchTerm = "";

   BookSearchAdapter adapter;

   private SearchTask searchTask;
   boolean allowSearchContinue;
   boolean prevSearchResultCount;

   @Override
   public void onCreate(final Bundle savedInstanceState) {
      super.onCreate(savedInstanceState);

      setContentView(R.layout.booksearch);
      application = (BookWormApplication) getApplication();

      searchTask = null;

      adapter = new BookSearchAdapter(new ArrayList<Book>());

      searchInput = (EditText) findViewById(R.id.bookentrysearchinput);
      // if user hits "enter" on keyboard, go ahead and submit, no need for newlines in the search box
      searchInput.setOnKeyListener(new OnKeyListener() {
         public boolean onKey(View v, int keyCode, KeyEvent event) {
            if ((event.getAction() == KeyEvent.ACTION_DOWN) && (keyCode == KeyEvent.KEYCODE_ENTER)) {
               // if the "enter" key is pressed start over (diff from "get more results")
               String searchTerm = searchInput.getText().toString();
               if ((searchTerm != null) && !searchTerm.equals("")) {
                  newSearch(searchTerm);
               }
               return true;
            }
            return false;
         }
      });

      searchButton = (Button) findViewById(R.id.bookentrysearchbutton);
      searchButton.setOnClickListener(new OnClickListener() {
         public void onClick(final View v) {
            // if the "search" button is pressed start over (diff from "get more results")
            String searchTerm = searchInput.getText().toString();
            if ((searchTerm != null) && !searchTerm.equals("")) {
               newSearch(searchTerm);
            }
         }
      });

      searchResults = (ListView) findViewById(R.id.bookentrysearchresultlist);
      searchResults.setTextFilterEnabled(true);
      searchResults.setOnItemClickListener(new OnItemClickListener() {
         public void onItemClick(final AdapterView<?> parent, final View v, final int index, final long id) {
            // don't redo the search, you have the BOOK itself (don't pass the ISBN, use the Book)
            application.selectedBook = adapter.getItem(index);
            selectorPosition = index;
            Intent intent = new Intent(BookSearch.this, BookEntryResult.class);
            intent.putExtra(BookSearch.FROM_SEARCH, true);
            startActivity(intent);
         }
      });
      searchResults.setOnScrollListener(new OnScrollListener() {
         public void onScroll(AbsListView v, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
            String searchTerm = searchInput.getText().toString();
            if (totalItemCount > 0 && (firstVisibleItem + visibleItemCount == totalItemCount)
                     && (searchTerm != null && !searchTerm.equals("")) && allowSearchContinue) {
               allowSearchContinue = false;
               selectorPosition = totalItemCount - 5;
               searchTask = new SearchTask();
               if (application.debugEnabled) {
                  Log.d(Constants.LOG_TAG, "search for term " + searchTerm + " starting at position " + searchPosition);
               }
               searchTask.execute(searchTerm, String.valueOf(searchPosition));
            }
         }

         public void onScrollStateChanged(AbsListView v, int scrollState) {
         }
      });
      searchResults.setAdapter(adapter);

      // do not enable the soft keyboard unless user explicitly selects textedit
      // Android seems to have an IMM bug concerning this on devices with only soft keyboard
      // http://code.google.com/p/android/issues/detail?id=7115
      getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

      // if coming from the search entry result page, try to re-establish prev adapter contents and positions
      if (getIntent().getBooleanExtra(BookEntryResult.FROM_RESULT, false)) {
         restoreFromStateBean(application.bookSearchStateBean);
      }
   }

   private void newSearch(final String searchTerm) {
      searchPosition = 0;
      selectorPosition = 0;
      prevSearchTerm = "";
      adapter.clear();
      adapter.notifyDataSetChanged();
      searchTask = new SearchTask();
      if (application.debugEnabled) {
         Log.i(Constants.LOG_TAG, "new search for term " + searchTerm + " starting at pos 0");
      }
      searchTask.execute(searchTerm, "0");
   }

   @Override
   public void onStart() {
      super.onStart();
   }

   @Override
   public void onPause() {
      if (searchTask != null) {
         TaskUtil.dismissDialog(searchTask.dialog);
      }
      TaskUtil.pauseTask(searchTask);
      application.bookSearchStateBean = createStateBean();
      super.onPause();
   }

   @Override
   public void onSaveInstanceState(Bundle outState) {
      super.onSaveInstanceState(outState);
   }

   @Override
   public void onRestoreInstanceState(Bundle inState) {
      super.onRestoreInstanceState(inState);
      restoreFromStateBean((BookSearchStateBean) getLastNonConfigurationInstance());
   }

   @Override
   public Object onRetainNonConfigurationInstance() {
      return createStateBean();
   }

   // go back to Main on back from here
   @Override
   public boolean onKeyDown(final int keyCode, final KeyEvent event) {
      if ((keyCode == KeyEvent.KEYCODE_BACK) && (event.getRepeatCount() == 0)) {
         startActivity(new Intent(BookSearch.this, Main.class));
         return true;
      }
      return super.onKeyDown(keyCode, event);
   }

   // use application object as quick/dirty cache for state
   // onRetainNonConfigurationInstance uses this to quickly save state on config changes
   // onPause uses this to save state longer term, when user "adds" book and then comes BACK to search   
   // TODO document this pattern 
   // onRetainNonConfigurationInstance, onSaveInstanceState/onRestoreInstanceState, createStateBean/restoreFromStateBean

   // restore from state bean (called from onRestoreInstanceState (using lastNonConfigurationInstance) and from onCreate (using application))
   private void restoreFromStateBean(BookSearchStateBean bean) {
      if (bean != null) {
         selectorPosition = bean.lastSelectorPosition;
         searchPosition = bean.lastSearchPosition;
         currSearchTerm = bean.lastSearchTerm;
         searchInput.setText(currSearchTerm);

         if (bean.books != null && !bean.books.isEmpty()) {
            for (Book b : bean.books) {
               boolean dupe = false;
               // this is very inefficient, but should be relatively small collections here, and must prevent dupes
               for (int j = 0; j < adapter.getCount(); j++) {
                  Book ab = adapter.getItem(j);
                  if (BookUtil.areBooksEffectiveDupes(ab, b)) {
                     if (application.debugEnabled) {
                        Log.d(Constants.LOG_TAG,
                                 "duplicate book detected on BookSearch restoreFromStateBean, it will not be added - "
                                          + b.title + " " + StringUtil.contractAuthors(b.authors));
                     }
                     dupe = true;
                     break;
                  }
               }
               if (!dupe) {
                  adapter.add(b);
               }
            }

            adapter.notifyDataSetChanged();
            if (adapter.getCount() >= selectorPosition) {
               searchResults.setSelection(selectorPosition);
            }
         }
         // any time we restore state, we can assume allowSearchContinue true (we don't want to prevent more data)
         allowSearchContinue = true;
      }
   }

   private BookSearchStateBean createStateBean() {
      BookSearchStateBean bean = new BookSearchStateBean();
      bean.lastSearchPosition = searchPosition;
      bean.lastSearchTerm = currSearchTerm;
      bean.lastSelectorPosition = selectorPosition;

      // store the current adapter contents
      ArrayList<Book> cacheList = new ArrayList<Book>();
      // again, should be able to get/put all from adapter? (and not just brute local access)
      for (int i = 0; i < adapter.getCount(); i++) {
         cacheList.add(adapter.getItem(i));
      }
      bean.books = cacheList;
      return bean;
   }

   // static and package access as an Android optimization (used in inner class)
   static class ViewHolder {
      TextView text1;
      TextView text2;
   }

   // BookSearchAdapter
   private class BookSearchAdapter extends ArrayAdapter<Book> {
      private ArrayList<Book> books;

      LayoutInflater vi = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);

      BookSearchAdapter(ArrayList<Book> bks) {
         super(BookSearch.this, R.layout.search_list_item, bks);
         books = bks;
      }

      @Override
      public View getView(int position, View convertView, ViewGroup parent) {

         View item = convertView;
         ViewHolder holder = null;

         if (item == null) {
            item = vi.inflate(R.layout.search_list_item, parent, false);
            // use ViewHolder pattern to avoid extra trips to findViewById         
            holder = new ViewHolder();
            holder.text1 = (TextView) item.findViewById(R.id.search_item_text_1);
            holder.text2 = (TextView) item.findViewById(R.id.search_item_text_2);
            item.setTag(holder);
         }

         holder = (ViewHolder) item.getTag();
         holder.text1.setText(books.get(position).title);
         holder.text2.setText(StringUtil.contractAuthors(books.get(position).authors));
         return item;
      }
   }

   //
   // AsyncTasks
   //
   private class SearchTask extends AsyncTask<String, Void, ArrayList<Book>> {
      private final ProgressDialog dialog = new ProgressDialog(BookSearch.this);

      // TODO hard coded to GoolgeBookDataSource for now
      private final GoogleBookDataSource gbs = new GoogleBookDataSource();

      public SearchTask() {
         gbs.setDebugEnabled(application.debugEnabled);
      }

      @Override
      protected void onPreExecute() {
         dialog.setMessage(getString(R.string.msgSearching));
         dialog.show();
      }

      @Override
      protected ArrayList<Book> doInBackground(final String... args) {
         String searchTerm = args[0];
         prevSearchTerm = currSearchTerm;
         currSearchTerm = searchTerm;
         int startIndex = Integer.valueOf(args[1]);
         if (searchTerm != null) {
            searchTerm = URLEncoder.encode(searchTerm);
            return gbs.getBooks(searchTerm, startIndex);
         }
         return null;
      }

      @Override
      protected void onPostExecute(final ArrayList<Book> searchBooks) {
         if (dialog.isShowing()) {
            dialog.dismiss();
         }

         if (!prevSearchTerm.equals(currSearchTerm)) {
            adapter.clear();
         }

         int dupeCount = 0;
         int addCount = 0;
         if (searchBooks != null && !searchBooks.isEmpty()) {
            if (application.debugEnabled) {
               Log.d(Constants.LOG_TAG, "Books parsed from data source:");
            }
            for (int i = 0; i < searchBooks.size(); i++) {
               Book b = searchBooks.get(i);
               boolean dupe = false;
               // this is very inefficient, need to figure out logical error (why dupes to begin with at this point)
               for (int j = 0; j < adapter.getCount(); j++) {
                  Book ab = adapter.getItem(j);
                  if (BookUtil.areBooksEffectiveDupes(ab, b)) {
                     if (application.debugEnabled) {
                        Log.i(Constants.LOG_TAG,
                                 "duplicate book detected on BookSearch searchTask, it will not be added - " + b.title
                                          + " " + StringUtil.contractAuthors(b.authors));
                     }
                     dupe = true;
                     dupeCount++;
                     break;
                  }
               }
               if (!dupe) {
                  addCount++;
                  adapter.add(b);
                  if (application.debugEnabled) {
                     Log.d(Constants.LOG_TAG, "  Book(" + i + "): " + b.title + " "
                              + StringUtil.contractAuthors(b.authors));
                  }
               }
            }
         }

         searchPosition = adapter.getCount() + 1;
         //searchPosition += 10;         

         adapter.notifyDataSetChanged();
         if (addCount > 0) {
            allowSearchContinue = true;
         } else {
            Toast.makeText(BookSearch.this, "No more results found for this term, please try another search term.",
                     Toast.LENGTH_LONG).show();
         }
      }
   }

   // state bean
   class BookSearchStateBean {
      ArrayList<Book> books;
      String lastSearchTerm;
      int lastSearchPosition;
      int lastSelectorPosition;
   }
}