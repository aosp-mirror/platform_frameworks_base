package com.android.example.bindingdemo;

import android.databinding.Bindable;
import android.databinding.Observable;
import android.databinding.OnPropertyChangedListener;
import android.support.v7.app.ActionBarActivity;
import android.os.Bundle;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import android.databinding.DataBindingUtil;
import android.databinding.PropertyChangeRegistry;
import com.android.example.bindingdemo.generated.ListItemBinding;
import com.android.example.bindingdemo.generated.MainActivityBinding;
import com.android.example.bindingdemo.vo.User;
import com.android.example.bindingdemo.vo.Users;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import  com.android.example.bindingdemo.BR;
public class MainActivity extends ActionBarActivity implements Observable {
    @Bindable
    UserAdapter tkAdapter;
    @Bindable
    UserAdapter robotAdapter;
    @Bindable
    MainActivityBinding dataBinder;
    @Bindable
    User selected;

    @Bindable
    User selected2;

   private final PropertyChangeRegistry mListeners = new PropertyChangeRegistry();

    public User getSelected2() {
        return selected2;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dataBinder =  MainActivityBinding.inflate(this);
        setContentView(dataBinder.getRoot());
        dataBinder.robotList.setHasFixedSize(true);
        dataBinder.toolkittyList.setHasFixedSize(true);
        tkAdapter = new UserAdapter(Users.toolkities);
        robotAdapter = new UserAdapter(Users.robots);
        dataBinder.toolkittyList.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        dataBinder.robotList.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        dataBinder.setActivity(this);
        dataBinder.executePendingBindings();
    }

    public UserAdapter getTkAdapter() {
        return tkAdapter;
    }

    public UserAdapter getRobotAdapter() {
        return robotAdapter;
    }

    public User getSelected() {
        return selected;
    }

    private void setSelected(User selected) {
        if (selected == this.selected) {
            return;
        }
        this.selected = selected;
        mListeners.notifyChange(this, BR.selected);
    }

    @Bindable
    public View.OnClickListener onSave = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            if (selected == null) {
                return;
            }
            selected.setName(dataBinder.selectedName.getText().toString());
            selected.setLastName(dataBinder.selectedLastname.getText().toString());
        }
    };

    @Bindable
    public View.OnClickListener onUnselect = new View.OnClickListener() {

        @Override
        public void onClick(View v) {
            setSelected(null);
        }
    };

    @Bindable
    public View.OnClickListener onDelete = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            if (selected == null) {
                return;
            }
            if (selected.getGroup() == User.TOOLKITTY) {
                tkAdapter.remove(selected);
                selected.setGroup(User.ROBOT);
                robotAdapter.add(selected);
                dataBinder.robotList.smoothScrollToPosition(robotAdapter.getItemCount() - 1);
            } else {
                tkAdapter.add(selected);
                dataBinder.toolkittyList.smoothScrollToPosition(tkAdapter.getItemCount() - 1);
                selected.setGroup(User.TOOLKITTY);
                robotAdapter.remove(selected);
            }
        }
    };


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void addOnPropertyChangedListener(OnPropertyChangedListener listener) {
        mListeners.add(listener);
    }

    @Override
    public void removeOnPropertyChangedListener(OnPropertyChangedListener listener) {
        mListeners.remove(listener);
    }

    public class UserAdapter extends DataBoundAdapter<ListItemBinding> implements View.OnClickListener, Observable {
        final private List<User> userList = new ArrayList<>();
        final private PropertyChangeRegistry mListeners = new PropertyChangeRegistry();

        public UserAdapter(User[] toolkities) {
            super(R.layout.list_item, ListItemBinding.class);
            userList.addAll(Arrays.asList(toolkities));
        }

        @Override
        public DataBoundViewHolder<ListItemBinding> onCreateViewHolder(ViewGroup viewGroup, int type) {
            DataBoundViewHolder<ListItemBinding> vh = super.onCreateViewHolder(viewGroup, type);
            vh.dataBinder.setClickListener(this);
            return vh;
        }

        @Override
        public void onBindViewHolder(DataBoundViewHolder<ListItemBinding> vh, int index) {
            vh.dataBinder.setUser(userList.get(index));
            vh.dataBinder.executePendingBindings();
        }

        @Bindable
        @Override
        public int getItemCount() {
            return userList.size();
        }

        public void add(User user) {
            if (userList.contains(user)) {
                return;
            }
            userList.add(user);
            notifyItemInserted(userList.size() - 1);
            mListeners.notifyChange(this, BR.itemCount);
        }

        public void remove(User user) {
            int i = userList.indexOf(user);
            if (i < 0) {
                return;
            }
            userList.remove(i);
            notifyItemRemoved(i);
            mListeners.notifyChange(this, BR.itemCount);
        }

        @Override
        public void onClick(View v) {
            RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) v.getLayoutParams();
            final int pos = lp.getViewPosition();
            if (pos > -1 && pos < userList.size()) {
                v.requestFocus();
                setSelected(userList.get(pos));
            } else {
                setSelected(null);
            }
        }

        @Override
        public void addOnPropertyChangedListener(OnPropertyChangedListener listener) {
            mListeners.add(listener);
        }

        @Override
        public void removeOnPropertyChangedListener(OnPropertyChangedListener listener) {
            mListeners.remove(listener);
        }
    }
}
